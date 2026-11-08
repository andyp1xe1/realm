package realm.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import realm.Filter
import realm.Home
import realm.Login
import realm.Model
import realm.Platform

@Composable
fun App(platform: Platform, modifier: Modifier = Modifier) {
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

        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
                SetupOrTabs(home)
                CloudflareSheet(home.cloudflare)
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)) {
                    Snackbar(
                        it,
                        shape = MaterialTheme.shapes.small,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
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

/** The current realm's feed, or the tab overview, under one shared top bar. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Tabs(home: Home, modifier: Modifier = Modifier) {
    var settings by remember { mutableStateOf(false) }
    val current = home.openKey.takeUnless { home.showingTabs || home.model == null }
    val open = current?.let { k -> home.spaces.firstOrNull { it.key == k } }
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar(
            title = open?.name,
            model = open?.let(home::modelOf),
            tabs = home.spaces.size,
            onTabs = { if (open == null) home.hideTabs() else home.showTabs() },
            onSettings = { settings = true },
            modifier = Modifier.widthIn(max = PageWidth),
            update = home.cloudflare.update != null,
        )
        SharedTransitionLayout(Modifier.weight(1f)) {
            AnimatedContent(
                current,
                transitionSpec = { fadeIn(tween(TAB_ZOOM_MILLIS)) togetherWith fadeOut(tween(TAB_ZOOM_MILLIS)) },
                contentAlignment = Alignment.TopCenter,
            ) { key ->
                CompositionLocalProvider(LocalSharedTransition provides this@SharedTransitionLayout, LocalTabTransition provides this) {
                    val space = key?.let { k -> home.spaces.firstOrNull { it.key == k } }
                    if (space == null) {
                        Grid(home)
                    } else {
                        Box(
                            Modifier.fillMaxSize().tabBounds(
                                space.key,
                                MaterialTheme.shapes.large,
                            ).background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Main(home.modelOf(space), space.name)
                        }
                    }
                }
            }
        }
    }
    if (settings) SettingsDialog(home, onDismiss = { settings = false })
}

/** One realm's feed, below the top bar. */
@Composable
fun Main(model: Model, title: String, modifier: Modifier = Modifier) {
    LaunchedEffect(model) { if (model.isOwner) model.loadLinks() }
    LaunchedEffect(model.query, model.filter) {
        if (model.query.isNotEmpty()) delay(250)
        model.refresh()
    }

    Column(
        modifier.widthIn(max = PageWidth).fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                e.key == Key.F && (e.isCtrlPressed || e.isMetaPressed) -> {
                    model.searching = true
                    true
                }

                e.key == Key.Escape && model.searching -> {
                    model.closeSearch()
                    true
                }

                else -> false
            }
        },
    ) {
        AnimatedVisibility(model.searching) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
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
            if (model.loading) {
                Delayed {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent,
                    )
                }
            }
        }

        var composerHeight by remember { mutableStateOf(ComposerHeight) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            var editorOpen by remember { mutableFloatStateOf(0f) }
            val faded = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - editorOpen }
            Feed(model, faded, bottomPadding = composerHeight, onShare = { model.sharing = true })
            Composer(model, Modifier.align(Alignment.BottomCenter), onCompactHeight = { composerHeight = it }, onOpen = { editorOpen = it })
        }
    }

    if (model.sharing) ShareDialog(model, title) { model.sharing = false }
}

@Composable
fun Wordmark(modifier: Modifier = Modifier, size: Int = 20) {
    Text("realm", modifier, fontSize = size.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
}

/** Height of outlined fields; buttons in the same form match it. */
val FormHeight = 56.dp

/** First run: log in with Cloudflare, or connect with a token. */
@Composable
fun Setup(home: Home, modifier: Modifier = Modifier) {
    var withToken by remember { mutableStateOf(false) }
    Column(
        modifier.widthIn(max = 420.dp).fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WelcomeArt()
        Spacer(Modifier.height(28.dp))
        Wordmark(size = 34)
        Spacer(Modifier.height(8.dp))
        Text("Your dump, everywhere.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        CloudflareButton(onClick = home.cloudflare::logIn, busy = home.cloudflare.login == Login.LoggingIn)
        Spacer(Modifier.height(12.dp))
        TokenButton(onClick = { withToken = true })
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = home::continueWithoutAccount) { Text("Continue without an account") }
    }

    if (withToken) Sheet({ withToken = false }) { TokenSheet(home) { withToken = false } }
}

private val CloudflareOrange = Color(0xFFF38020)

/** Logs in with Cloudflare. [busy] while the browser is out; tapping again starts over. */
@Composable
fun CloudflareButton(onClick: () -> Unit, modifier: Modifier = Modifier, busy: Boolean = false) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(FormHeight),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = CloudflareOrange, contentColor = Color.White),
    ) {
        Icon(Cloudflare, null, Modifier.size(26.dp), tint = Color.White)
        Spacer(Modifier.width(10.dp))
        Text(if (busy) "Logging in…" else "Log in with Cloudflare", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun TokenButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(FormHeight),
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text("Connect with a token", color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun TokenSheetPreview(home: Home) = TokenSheet(home) {}

@Composable
private fun TokenSheet(home: Home, onDone: () -> Unit) {
    Column {
        Text("Connect with a token", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Your server's address and its owner token.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        SignInForm(home, onDone = onDone)
    }
}

/** A server URL and its owner token; [onDone] runs after signing in. */
@Composable
fun SignInForm(home: Home, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun go() {
        if (url.isBlank() || busy || token.isBlank()) return
        busy = true
        error = null
        scope.launch {
            error = home.signIn(url, token.trim())
            busy = false
            if (error == null) onDone()
        }
    }

    Column(modifier) {
        OutlinedTextField(
            url,
            { url = it },
            label = { Text("Server") },
            placeholder = { Text("realm.you.workers.dev") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        Spacer(Modifier.height(12.dp))
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
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = ::go,
            enabled = !busy && url.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(FormHeight),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(if (busy) "Signing in…" else "Sign in")
        }
    }
}

@Composable
fun SectionDivider(modifier: Modifier = Modifier) = HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant)
