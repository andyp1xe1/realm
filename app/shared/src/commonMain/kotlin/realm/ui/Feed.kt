package realm.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import realm.Item
import realm.Model
import realm.Pending
import realm.Platform
import androidx.compose.ui.graphics.ImageBitmap
import realm.humanSize

@Composable
fun Feed(model: Model, modifier: Modifier = Modifier, bottomPadding: Dp = 0.dp, onShare: () -> Unit = {}) {
    val state = model.listState
    val platform = model.platform

    // Newest at the bottom, like a chat. Index 0 is the bottom-most row.
    val nearTop by remember {
        derivedStateOf {
            val info = state.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearTop, model.items.size) { if (nearTop) model.loadMore() }

    val newest = model.items.firstOrNull()?.id
    LaunchedEffect(newest, model.pending.size) {
        if (state.firstVisibleItemIndex < 4) state.animateScrollToItem(0)
    }

    // Known to be empty, not just not loaded yet. Stays up through a reload, so it doesn't blink out and back.
    if (model.items.isEmpty() && model.pending.isEmpty() && model.loaded) {
        Box(modifier.fillMaxSize().padding(bottom = bottomPadding), contentAlignment = Alignment.Center) {
            when {
                !model.canRead -> Text("This link can only add things. Dump away.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                model.filtering -> Text("Nothing matches.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> EmptyRealm(onShare = onShare)
            }
        }
        return
    }

    LazyColumn(
        modifier.fillMaxSize(),
        state = state,
        reverseLayout = true,
        contentPadding = PaddingValues(bottom = bottomPadding),
        // Bottom: a short feed sits on the composer like a chat, not at the top with a gap below.
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        // Rows fade in the first time they're shown: the first page, older pages, other devices, what you send.
        items(model.pending.reversed(), key = { "p${it.key}" }) {
            Box(Modifier.fadeInOnce(model, "p${it.key}")) { PendingRow(it, model) }
        }
        items(model.items.size, key = { model.items[it].id }) { i ->
            val item = model.items[i]
            val older = model.items.getOrNull(i + 1)
            val day = platform.formatDay(item.created)
            Column(Modifier.fadeInOnce(model, item.id)) {
                if (older == null || platform.formatDay(older.created) != day) DayHeader(day)
                ItemRow(item, model)
            }
        }
    }
}

private fun isInlineImage(item: Item) = item.kind == "image" && (item.size ?: 0) <= Model.MAX_INLINE_IMAGE

private fun metaText(item: Item, platform: Platform) = buildString {
    append(platform.formatTime(item.created))
    item.src?.takeIf { it != platform.name }?.let { append(" · ").append(it) }
}

/** What's in an item's bubble; [image] is the picture, once there is one. */
@Composable
private fun ItemBody(item: Item, image: ImageBitmap?) {
    when {
        isInlineImage(item) -> ImageContent(item, image)
        item.name != null -> Column {
            FileRow(item.name, fileDetails(item.size, item.name)) { KindIcon(item.kind, item.mime) }
            Caption(item.text)
        }
        else -> NoteContent(item.text.orEmpty())
    }
}

/**
 * The feed as a plain picture: the same rows, oldest first, with nothing to tap and nothing lazy.
 * Tab tiles draw it at page size and shrink it, so it zooms seamlessly into the real feed.
 */
@Composable
fun FeedPicture(items: List<Item>, platform: Platform, image: (Item) -> ImageBitmap?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        var day: String? = null
        for (item in items.asReversed()) {
            val d = platform.formatDay(item.created)
            if (d != day) DayHeader(d)
            day = d
            Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 2.dp)) {
                Bubble { ItemBody(item, image(item)) }
                Meta(metaText(item, platform))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Fades a row in the first time this realm shows it. Rows scrolled back into view, or seen again after a trip
 * through the tabs, are already there.
 */
@Composable
private fun Modifier.fadeInOnce(model: Model, key: String): Modifier {
    val alpha = remember(key) { Animatable(if (model.firstShown(key)) 0f else 1f) }
    LaunchedEffect(key) { alpha.animateTo(1f, tween(260)) }
    return graphicsLayer { this.alpha = alpha.value }
}

@Composable
private fun DayHeader(day: String) {
    Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            day,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** The rounded card every row sits in, sent or still sending. */
@Composable
private fun Bubble(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.widthIn(max = 560.dp),
        content = content,
    )
}

/** The small line under a bubble: time · source, or the sending state. */
@Composable
private fun Meta(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), actions: @Composable () -> Unit = {}) {
    Row(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 4.dp).heightIn(min = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
        actions()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(item: Item, model: Model) {
    var menu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf(false) }
    val isFile = item.name != null
    val tap: () -> Unit = if (isFile) ({ model.open(item) }) else ({ menu = true })

    Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 2.dp)) {
        Box {
            Bubble(
                Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .pointerInput(item.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                                    e.changes.forEach { it.consume() }
                                    menu = true
                                }
                            }
                        }
                    }
                    .combinedClickable(onClick = tap, onLongClick = { menu = true }),
            ) {
                if (isInlineImage(item)) LaunchedEffect(item.id) { model.loadImage(item) }
                ItemBody(item, model.image(item.id))
            }
            ItemMenu(item, model, menu, onDismiss = { menu = false }, onView = { viewing = true })
        }
        Meta(metaText(item, model.platform))
    }

    if (viewing) TextViewer(item.text.orEmpty(), onCopy = { model.copy(item) }) { viewing = false }
}

private val URL = Regex("""https?://[^\s<>"']+[^\s<>"'.,;:!?)\]]""")

@Composable
private fun linkify(text: String): AnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return remember(text, style) {
        buildAnnotatedString {
            var last = 0
            for (m in URL.findAll(text)) {
                append(text, last, m.range.first)
                withLink(LinkAnnotation.Url(m.value, style)) { append(m.value) }
                last = m.range.last + 1
            }
            append(text, last, text.length)
        }
    }
}

@Composable
private fun NoteContent(text: String) {
    Text(
        linkify(text),
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 24,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun Caption(text: String?) {
    if (text.isNullOrBlank()) return
    Text(
        linkify(text),
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 12,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
    )
}

@Composable
private fun ImageContent(item: Item, image: ImageBitmap?) {
    Column(Modifier.widthIn(max = 340.dp).fillMaxWidth()) {
        // The picture fades in over its placeholder once it has loaded.
        Crossfade(image, animationSpec = tween(260)) { bmp ->
            if (bmp != null) {
                val ratio = (bmp.width.toFloat() / bmp.height).coerceIn(0.5f, 2.5f)
                Image(
                    bmp, item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).aspectRatio(ratio),
                )
            } else {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                }
            }
        }
        if (!item.text.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Caption(item.text)
        }
    }
}

/** Icon tile + name + details. The tile holds the type icon, or the progress ring while uploading. */
@Composable
private fun FileRow(title: String, details: String, detailsColor: Color = MaterialTheme.colorScheme.onSurfaceVariant, tile: @Composable () -> Unit) {
    Row(Modifier.padding(10.dp).widthIn(min = 220.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(42.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { tile() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.padding(end = 6.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = detailsColor)
        }
    }
}

@Composable
internal fun KindIcon(kind: String, mime: String?, modifier: Modifier = Modifier) {
    Icon(
        when (kind) {
            "audio" -> Icons.Outlined.Audiotrack
            "video" -> Icons.Outlined.Movie
            "image" -> Icons.Outlined.Image
            else -> if (mime?.startsWith("text/") == true) Icons.Outlined.Description else Icons.Outlined.InsertDriveFile
        },
        null,
        modifier,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

private fun fileDetails(size: Long?, name: String?) =
    listOfNotNull(humanSize(size).ifEmpty { null }, name?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 }?.uppercase())
        .joinToString(" · ")

@Composable
private fun ItemMenu(item: Item, model: Model, open: Boolean, onDismiss: () -> Unit, onView: () -> Unit) {
    var linkStep by remember(open) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    DropdownMenu(open, onDismiss, shape = MaterialTheme.shapes.small, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        fun act(f: () -> Unit) = { f(); onDismiss() }
        val isFile = item.name != null
        if (linkStep) {
            TTLS.forEach { (label, ttl) ->
                DropdownMenuItem(text = { Text(label) }, onClick = act { model.shareLink(item, ttl) })
            }
        } else {
            if (isFile) {
                DropdownMenuItem(text = { Text("Open") }, onClick = act { model.open(item) })
                DropdownMenuItem(text = { Text("Save") }, onClick = act { model.save(item) })
            }
            if (!item.text.isNullOrEmpty()) {
                DropdownMenuItem(text = { Text(if (isFile) "Copy caption" else "Copy") }, onClick = act { model.copy(item) })
                if (!isFile && (item.text.length > 800 || item.text.count { it == '\n' } > 20)) {
                    DropdownMenuItem(text = { Text("View all") }, onClick = act(onView))
                }
            }
            DropdownMenuItem(text = { Text(if (isFile) "Edit caption" else "Edit") }, onClick = act { model.editing = item })
            if (model.isOwner) DropdownMenuItem(text = { Text("Share link…") }, onClick = { linkStep = true })
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = act { deleting = true },
            )
        }
    }
    if (deleting) {
        Confirm(
            item.name?.let { "Delete “$it”?" } ?: if (item.kind == "link") "Delete this link?" else "Delete this note?",
            "It's gone for good, and links that share only it stop working.",
            "Delete",
            onDismiss = { deleting = false },
        ) { model.delete(item) }
    }
}

val TTLS = listOf("1 hour" to 3600L, "1 day" to 86_400L, "7 days" to 604_800L, "30 days" to 2_592_000L, "Forever" to null)

/** Something on its way up. Drawn exactly like the item it will become, so nothing jumps when it lands. */
@Composable
private fun PendingRow(p: Pending, model: Model) {
    val failed = p.error != null
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Bubble(Modifier.alpha(if (failed || p.file != null) 1f else 0.6f)) {
            val file = p.file
            if (file == null) {
                NoteContent(p.text.orEmpty())
            } else Column {
                val size = file.size.takeIf { it > 0 }
                FileRow(
                    file.name,
                    when {
                        failed -> p.error!!
                        size == null -> "uploading…"
                        else -> "${humanSize((size * p.progress).toLong())} of ${humanSize(size)}"
                    },
                    detailsColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    if (failed) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    } else {
                        val progress by animateFloatAsState(p.progress)
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                            gapSize = 0.dp,
                        )
                    }
                }
                Caption(p.caption)
            }
        }
        if (failed) {
            Meta("failed", MaterialTheme.colorScheme.error) {
                MetaAction("retry") { model.retry(p) }
                MetaAction("dismiss") { model.dismiss(p) }
            }
        } else {
            Meta("sending…")
        }
    }
}

@Composable
private fun MetaAction(label: String, onClick: () -> Unit) {
    Text(
        " · $label",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun TextViewer(text: String, onCopy: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(20.dp)) {
                SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(linkify(text), style = MaterialTheme.typography.bodyLarge)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCopy) { Text("Copy") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}
