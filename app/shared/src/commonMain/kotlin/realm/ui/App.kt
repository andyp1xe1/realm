package realm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import realm.Home
import realm.Model
import realm.Platform
import realm.Filter

@Composable
fun App(platform: Platform) {
    RealmTheme {
        val scope = rememberCoroutineScope()
        val home = remember { Home(platform, scope) }
        val model = home.model
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(home.message, model?.message) {
            (home.message ?: model?.message)?.let {
                home.message = null
                model?.message = null
                snackbar.showSnackbar(it)
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
                if (home.isEmpty) Setup(home) else Tabs(home)
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)) {
                    Snackbar(it, shape = MaterialTheme.shapes.small, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        LaunchedEffect(Unit) { platform.incoming.collect(home::receive) }
        if (model != null) {
            LaunchedEffect(model) {
                while (true) {
                    delay(8_000)
                    if (platform.isForeground) model.poll()
                }
            }
        }
    }
}

/** The current realm's feed, or the tab overview zoomed out from it. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Tabs(home: Home) {
    // Keyed by the realm, not the Space: a fresh Space each recomposition would restart the animation.
    val current = home.openKey.takeUnless { home.showingTabs || home.model == null }
    SharedTransitionLayout {
        AnimatedContent(
            current,
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(160)) },
            contentAlignment = Alignment.TopCenter,
        ) { key ->
            CompositionLocalProvider(LocalSharedTransition provides this@SharedTransitionLayout, LocalTabTransition provides this) {
                val space = key?.let { k -> home.spaces.firstOrNull { it.key == k } }
                if (space == null) {
                    Grid(home)
                } else {
                    Box(
                        Modifier.fillMaxSize().tabBounds(space.key, MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Main(home.modelOf(space), space.name, tabs = home.spaces.size, onTabs = home::showTabs)
                    }
                }
            }
        }
    }
}

/** One realm's feed. [onTabs] zooms out to the tab overview. */
@Composable
fun Main(model: Model, title: String, tabs: Int = 1, onTabs: () -> Unit = {}) {
    var searching by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(model.query, model.filter) {
        if (model.query.isNotEmpty()) delay(250)
        model.refresh()
    }

    fun closeSearch() {
        searching = false
        model.query = ""
        model.filter = Filter.All
    }


    Column(
        Modifier.widthIn(max = PageWidth).fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                e.key == Key.F && (e.isCtrlPressed || e.isMetaPressed) -> { searching = true; true }
                e.key == Key.Escape && searching -> { closeSearch(); true }
                else -> false
            }
        },
    ) {
        // top bar
        Row(Modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (searching) {
                IconButton(onClick = ::closeSearch) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
                var q by remember { mutableStateOf(TextFieldValue(model.query)) }
                Field(
                    value = q,
                    onValueChange = { q = it; model.query = it.text },
                    placeholder = "Search the dump",
                    singleLine = true,
                    modifier = Modifier.weight(1f).focusRequester(searchFocus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { model.refresh() }),
                )
                if (q.text.isNotEmpty()) IconButton(onClick = { q = TextFieldValue(""); model.query = "" }) { Icon(Icons.Outlined.Close, "Clear") }
                LaunchedEffect(Unit) { searchFocus.requestFocus() }
            } else {
                PageTitle(title, Modifier.weight(1f))
                ShareButton { showShare = true }
                IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Search") }
                TabsButton(tabs, onTabs)
            }
        }
        AnimatedVisibility(searching) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Filter.entries.forEach { f ->
                    FilterChip(
                        selected = model.filter == f,
                        onClick = { model.filter = f },
                        label = { Text(f.label) },
                        shape = CircleShape,
                        border = null,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent)
        }

        var composerHeight by remember { mutableStateOf(72.dp) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Feed(model, Modifier.fillMaxSize(), bottomPadding = composerHeight)
            Composer(model, Modifier.align(Alignment.BottomCenter), onCompactHeight = { composerHeight = it })
        }
    }

    if (showShare) ShareDialog(model, title) { showShare = false }
}

/** Height of both screens' top bars; their text starts where the content's gutter does, plus a little. */
val TopBarHeight = 56.dp

/** The top bar's title, lined up with the page content below it: realm/<name>, or just realm. */
@Composable
fun PageTitle(realm: String?, modifier: Modifier = Modifier) {
    Wordmark(realm = realm, modifier = modifier.padding(start = PageGutter))
}

/** "realm", or "realm/Main" inside a realm, with the "realm/" part muted. */
@Composable
fun Wordmark(size: Int = 20, realm: String? = null, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            if (realm == null) append("realm") else {
                withStyle(SpanStyle(color = muted)) { append("realm/") }
                append(realm)
            }
        },
        modifier,
        fontSize = size.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Outlined fields are 56dp; buttons in the same form match them. */
val FormHeight = 56.dp

/** First run: nothing signed in, nothing connected. */
@Composable
fun Setup(home: Home) {
    Column(
        Modifier.widthIn(max = 420.dp).fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Wordmark(34)
        Spacer(Modifier.height(8.dp))
        Text("Your dump, everywhere.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        SignInForm(home)
    }
}

/**
 * One field for either way in: a server URL asks for its owner token and signs in, a share link
 * (…/s/<token>) connects as one more tile. [onDone] runs after either succeeds.
 */
@Composable
fun SignInForm(home: Home, linksOnly: Boolean = false, onDone: () -> Unit = {}) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isLink = linksOnly || "/s/" in url

    fun go() {
        if (url.isBlank() || busy || (!isLink && token.isBlank())) return
        busy = true
        error = null
        scope.launch {
            val u = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
            error = if (isLink) home.connect(u) else home.signIn(u, token.trim())
            busy = false
            if (error == null) onDone()
        }
    }

    Column {
        OutlinedTextField(
            url, { url = it },
            label = { Text(if (linksOnly) "Share link" else "Server or share link") },
            placeholder = { Text(if (linksOnly) "realm.someone.dev/s/…" else "realm.you.workers.dev") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = if (isLink) ImeAction.Go else ImeAction.Next),
            keyboardActions = KeyboardActions(onGo = { go() }),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        Spacer(Modifier.height(12.dp))
        AnimatedVisibility(!isLink) {
            OutlinedTextField(
                token, { token = it },
                label = { Text("Owner token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = ::go,
            enabled = !busy && url.isNotBlank() && (isLink || token.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(FormHeight),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                when {
                    busy && isLink -> "Connecting…"
                    busy -> "Signing in…"
                    isLink -> "Connect"
                    else -> "Sign in"
                },
            )
        }
    }
}

@Composable
fun SectionDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
