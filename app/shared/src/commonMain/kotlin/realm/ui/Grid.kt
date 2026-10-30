package realm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import realm.Home
import realm.Incoming
import realm.Item
import realm.Model
import realm.Space

/** The overview: every realm and connected link as a tile, like a browser's tab overview. */
@Composable
fun Grid(home: Home) {
    var adding by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { home.refresh() }

    Column(Modifier.widthIn(max = 1100.dp).fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark()
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, "Settings") }
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (home.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent)
        }
        home.incoming?.let { IncomingBanner(it) { home.incoming = null } }
        LazyVerticalGrid(
            GridCells.Adaptive(150.dp),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(home.spaces, key = { it.key }) { Tile(home, it, dimmed = home.incoming != null && !it.canWrite) }
            item(key = "add") { AddTile(if (home.server != null) "New realm" else "Add a link") { adding = true } }
        }
    }

    if (adding) Sheet({ adding = false }) { AddSheet(home) { adding = false } }
    if (settings) SettingsDialog(home) { settings = false }
}

@Composable
private fun IncomingBanner(incoming: Incoming, onCancel: () -> Unit) {
    val what = when (incoming) {
        is Incoming.Files -> if (incoming.files.size == 1) "this file" else "${incoming.files.size} files"
        is Incoming.Text -> "this text"
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pick a realm for $what", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** Shape of every tile's card: portrait, like a page. */
private const val TileRatio = 0.78f

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(home: Home, space: Space, dimmed: Boolean) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Column(Modifier.alpha(if (dimmed) 0.4f else 1f)) {
        Box {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(TileRatio)
                    .clip(MaterialTheme.shapes.large)
                    .pointerInput(space.key) {
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
                    .combinedClickable(enabled = !dimmed, onClick = { home.open(space) }, onLongClick = { menu = true }),
            ) {
                Box {
                    TilePreview(home, space)
                    if (space is Space.Shared) SharedBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.small) {
                fun act(f: () -> Unit) = { menu = false; f() }
                DropdownMenuItem(text = { Text("Open") }, onClick = act { home.open(space) })
                when (space) {
                    is Space.Own -> {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = act { renaming = true })
                        DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = act { deleting = true })
                    }
                    is Space.Shared -> {
                        DropdownMenuItem(text = { Text("Copy link") }, onClick = act { home.platform.copyText(space.url); home.message = "Link copied" })
                        DropdownMenuItem(text = { Text("Remove", color = MaterialTheme.colorScheme.error) }, onClick = act { home.disconnect(space) })
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            space.name,
            Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            details(home, space),
            Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (space is Space.Shared && space.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (space is Space.Own) {
        if (renaming) Sheet({ renaming = false }) {
            NameSheet("Rename realm", space.realm.name, "Rename", onClose = { renaming = false }) { home.renameRealm(space.realm, it) }
        }
        if (deleting) Sheet({ deleting = false }) {
            Text("Delete “${space.name}”?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Everything in it goes, and every link to it stops working. This can't be undone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { deleting = false }) { Text("Cancel") }
                TextButton(onClick = { deleting = false; home.deleteRealm(space.realm) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun details(home: Home, space: Space): String = when (space) {
    is Space.Own -> {
        val n = space.realm.count
        listOfNotNull(
            if (n == 1L) "1 item" else "$n items",
            space.realm.updated?.let { home.platform.formatDay(it) },
        ).joinToString(" · ")
    }
    is Space.Shared -> space.error ?: space.info.let { info ->
        when {
            info == null -> "Shared"
            info.item != null -> "Shared file"
            info.items != null -> "Shared · ${info.items} items"
            info.scope == "drop" -> "Shared · add only"
            info.scope == "read" -> "Shared · read only"
            else -> "Shared"
        }
    }
}

@Composable
private fun SharedBadge(modifier: Modifier) {
    Box(
        modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.Link, "Shared with you", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** What's inside, small: the latest items stacked like the feed, newest at the bottom. */
@Composable
private fun TilePreview(home: Home, space: Space) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shared = space as? Space.Shared
    when {
        shared?.error != null -> Centered { Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) }
        shared?.info?.item != null -> SoleItem(home, space, shared.info.item)
        space.canWrite && !space.canRead -> Centered {
            Icon(Icons.Outlined.FileUpload, null, Modifier.size(28.dp), tint = muted)
            Spacer(Modifier.height(6.dp))
            Text("Drop things here", style = MaterialTheme.typography.labelSmall, color = muted)
        }
        else -> {
            val items = home.preview(space)
            if (items.isEmpty()) {
                if (space is Space.Own && space.realm.count == 0L) Centered { Text("Empty", style = MaterialTheme.typography.labelSmall, color = muted) }
                return
            }
            // Pinned to the bottom like the feed, so what doesn't fit is the oldest, cut off at the top.
            Box(Modifier.fillMaxSize().clipToBounds()) {
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    items.reversed().forEach { MiniItem(home, space, it) }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { content() }
}

@Composable
private fun SoleItem(home: Home, space: Space, item: Item) {
    if (item.kind == "image") {
        val bmp = home.thumb(item.id)
        LaunchedEffect(item.id) { home.loadThumb(space, item) }
        if (bmp != null) return Image(bmp, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
    Centered {
        Box(
            Modifier.size(52.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { KindIcon(item.kind, item.mime) }
        item.name?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A feed row at tile scale: images as thumbnails, files as icon + name, notes as a line or two. */
@Composable
private fun MiniItem(home: Home, space: Space, item: Item) {
    val bubble = Modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest)
    if (item.kind == "image" && (item.size ?: 0) <= Model.MAX_INLINE_IMAGE) {
        val bmp = home.thumb(item.id)
        LaunchedEffect(item.id) { home.loadThumb(space, item) }
        Box(bubble.fillMaxWidth().height(64.dp)) {
            bmp?.let { Image(it, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        return
    }
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp)
    if (item.name != null) {
        Row(bubble.padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(18.dp).clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { KindIcon(item.kind, item.mime, Modifier.size(12.dp)) }
            Spacer(Modifier.width(5.dp))
            Text(item.name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Text(item.text.orEmpty(), bubble.padding(horizontal = 7.dp, vertical = 5.dp), style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AddTile(label: String, onClick: () -> Unit) {
    Column {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.large,
            color = Color.Transparent,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().aspectRatio(TileRatio),
        ) {
            Centered { Icon(Icons.Outlined.Add, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Signed in: a name makes a realm, a share link connects. Otherwise only links. */
@Composable
private fun AddSheet(home: Home, onClose: () -> Unit) {
    if (home.server == null) {
        Text("Add a share link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("It shows up here next to your other realms.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        SignInForm(home, linksOnly = true, onDone = onClose)
        return
    }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isLink = "/s/" in text

    fun go() {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        if (!isLink) {
            home.createRealm(t)
            return onClose()
        }
        busy = true
        scope.launch {
            error = home.connect(if (t.startsWith("http")) t else "https://$t")
            busy = false
            if (error == null) onClose()
        }
    }

    Text("New realm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "Name it, or paste a share link someone sent you to keep it here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        text, { text = it; error = null },
        placeholder = { Text("Name or share link") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { go() }),
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
    )
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = onClose) { Text("Cancel") }
        Button(onClick = ::go, enabled = text.isNotBlank() && !busy, shape = MaterialTheme.shapes.small) {
            Text(if (isLink) (if (busy) "Connecting…" else "Connect") else "Create")
        }
    }
}

@Composable
private fun NameSheet(title: String, initial: String, confirm: String, onClose: () -> Unit, onName: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    fun go() {
        if (text.isBlank()) return
        onName(text.trim())
        onClose()
    }
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        text, { text = it },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { go() }),
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
    )
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = onClose) { Text("Cancel") }
        Button(onClick = ::go, enabled = text.isNotBlank(), shape = MaterialTheme.shapes.small) { Text(confirm) }
    }
}
