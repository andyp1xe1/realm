package realm.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import realm.Home
import realm.Incoming
import realm.Item
import realm.Model
import realm.Space

/**
 * The overview: every realm and connected link as a tile, like a browser's tab overview. The current one
 * is outlined; the tabs button, in the same corner as on the feed, goes back to it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Grid(home: Home) {
    var adding by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val spaces = home.spaces
    // Start scrolled to the current tab, so the feed has a tile on screen to zoom into.
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = spaces.indexOfFirst { it.key == home.openKey } + 1)
    LaunchedEffect(Unit) { home.refresh() }
    // A tile just made or connected: bring it into view, let it arrive, then zoom into it from there.
    LaunchedEffect(home.fresh) {
        val key = home.fresh ?: return@LaunchedEffect
        val i = home.spaces.indexOfFirst { it.key == key }
        if (i >= 0) grid.animateScrollToItem(i + 1)
        delay(TileArriveMillis.toLong() + 80)
        home.spaces.firstOrNull { it.key == key }?.let(home::open)
    }
    BackHandler(enabled = home.open != null) { home.hideTabs() }

    Column(Modifier.widthIn(max = PageWidth).fillMaxSize()) {
        Row(Modifier.topBarBounds().fillMaxWidth().height(TopBarHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PageTitle(null, Modifier.weight(1f))
            IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, "Settings") }
            TabsButton(spaces.size) { home.hideTabs() }
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (home.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent)
        }
        home.incoming?.let { IncomingBanner(it) { home.incoming = null } }
        // Tiles draw their feed laid out this wide, then shrink it, so it matches the feed they zoom into.
        val page = LocalWindowInfo.current.containerDpSize.width.coerceAtMost(PageWidth)
        LazyVerticalGrid(
            GridCells.Adaptive(150.dp),
            Modifier.fillMaxSize(),
            state = grid,
            contentPadding = PaddingValues(start = PageGutter, end = PageGutter, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(PageGutter),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "add") { AddTile(if (home.server != null) "New realm" else "Add a link") { adding = true } }
            items(spaces, key = { it.key }) {
                // When a tile goes, the ones after it slide over rather than jump.
                Tile(
                    home, it, page,
                    current = it.key == home.openKey,
                    dimmed = home.incoming != null && !it.canWrite,
                    modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                )
            }
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
        modifier = Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, top = 8.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pick a realm for $what", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** How long a new tile takes to fade and grow into place before it opens. */
private const val TileArriveMillis = 300

/** Tiles are square. */
private const val TileRatio = 1f

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(home: Home, space: Space, page: Dp, current: Boolean, dimmed: Boolean, modifier: Modifier = Modifier) {
    // A new tile fades and grows into place, a deleted one fades and shrinks away; the rest are simply there.
    val leaving = space.key in home.leaving
    val arrive = remember(space.key) { Animatable(if (space.key == home.fresh) 0f else 1f) }
    LaunchedEffect(space.key, leaving) {
        arrive.animateTo(if (leaving) 0f else 1f, tween(if (leaving) Home.TileLeaveMillis.toInt() else TileArriveMillis))
    }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    Column(
        modifier.graphicsLayer {
            alpha = arrive.value * if (dimmed) 0.4f else 1f
            scaleX = 0.9f + 0.1f * arrive.value
            scaleY = scaleX
        },
    ) {
        Box {
            Surface(
                shape = MaterialTheme.shapes.large,
                // The page's own colour: the tile is a picture of the page.
                color = MaterialTheme.colorScheme.background,
                border = if (current) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(TileRatio)
                    .tabBounds(space.key, MaterialTheme.shapes.large)
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
                    .combinedClickable(enabled = !dimmed && !leaving, onClick = { home.open(space) }, onLongClick = { menu = true }),
            ) {
                Box {
                    TilePreview(home, space, page)
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
                        DropdownMenuItem(text = { Text("Remove", color = MaterialTheme.colorScheme.error) }, onClick = act { removing = true })
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
        if (deleting) {
            Confirm(
                "Delete “${space.name}”?",
                "Everything in it goes, and every link to it stops working. This can't be undone.",
                "Delete",
                onDismiss = { deleting = false },
            ) { home.deleteRealm(space.realm) }
        }
    }
    if (space is Space.Shared && removing) {
        Confirm(
            "Remove “${space.name}”?",
            "It leaves your tabs. The link itself keeps working, so you can add it again.",
            "Remove",
            onDismiss = { removing = false },
        ) { home.disconnect(space) }
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
            info.items == 1 -> "Shared · 1 file"
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

/** What's inside: for a realm you can read (a link to picked items too), a shrunken picture of its feed; otherwise what the link is for. */
@Composable
private fun TilePreview(home: Home, space: Space, page: Dp) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shared = space as? Space.Shared
    when {
        shared?.error != null -> Centered { Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) }
        space.canWrite && !space.canRead -> Centered {
            Icon(Icons.Outlined.FileUpload, null, Modifier.size(28.dp), tint = muted)
            Spacer(Modifier.height(6.dp))
            Text("Drop things here", style = MaterialTheme.typography.labelSmall, color = muted)
        }
        else -> {
            val items = home.preview(space)
            LaunchedEffect(items) { items.filter { it.kind == "image" }.forEach { home.loadThumb(space, it) } }
            Miniature(page) {
                Column(Modifier.fillMaxSize()) {
                    // Pinned to the bottom like the feed, so what doesn't fit is the oldest, cut off at the top.
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                        // As on the page: the empty-realm art, centred above the composer.
                        if (home.isEmpty(space)) EmptyRealm(Modifier.align(Alignment.Center), still = true)
                        if (items.isNotEmpty()) {
                            FeedPicture(
                                items, home.platform, { home.image(space, it) },
                                Modifier.align(Alignment.BottomStart).fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true),
                            )
                        }
                    }
                    // Where the composer sits on the real page.
                    if (space.canWrite) {
                        Box(
                            Modifier.fillMaxWidth().padding(8.dp).height(56.dp)
                                .clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer),
                        )
                    }
                }
            }
        }
    }
}

/** Lays [content] out [width] wide, as on the real page, then shrinks it to the space it's given. */
@Composable
private fun Miniature(width: Dp, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val scale = maxWidth / width
        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(width, maxHeight / scale)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) { content() }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { content() }
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
