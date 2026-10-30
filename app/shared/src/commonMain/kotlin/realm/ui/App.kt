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
import androidx.compose.material.icons.outlined.Link
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
import realm.Incoming
import realm.Model
import realm.Platform
import realm.Filter

@Composable
fun App(platform: Platform) {
    RealmTheme {
        val scope = rememberCoroutineScope()
        val model = remember { Model(platform, scope) }
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(model.message) {
            model.message?.let {
                model.message = null
                snackbar.showSnackbar(it)
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
                if (model.api == null) Setup(model) else Main(model)
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)) {
                    Snackbar(it, shape = MaterialTheme.shapes.small, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        if (model.api != null) {
            LaunchedEffect(model.api) {
                platform.incoming.collect {
                    when (it) {
                        // Nothing from outside is sent straight away: it lands in the composer to be captioned/edited.
                        is Incoming.Files -> {
                            model.stage(it.files)
                            it.caption?.let { c -> model.incomingText = c }
                        }
                        is Incoming.Text -> model.incomingText = it.text
                    }
                }
            }
            LaunchedEffect(model.api) {
                while (true) {
                    delay(8_000)
                    if (platform.isForeground) model.poll()
                }
            }
        }
    }
}

@Composable
fun Main(model: Model) {
    var searching by remember { mutableStateOf(false) }
    var showLinks by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
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
        Modifier.widthIn(max = 820.dp).fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                e.key == Key.F && (e.isCtrlPressed || e.isMetaPressed) -> { searching = true; true }
                e.key == Key.Escape && searching -> { closeSearch(); true }
                else -> false
            }
        },
    ) {
        // top bar
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = if (searching) 4.dp else 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Wordmark()
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Search") }
                if (model.isOwner) IconButton(onClick = { showLinks = true }) { Icon(Icons.Outlined.Link, "Share links") }
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Settings, "Settings") }
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

    if (showLinks) LinksDialog(model) { showLinks = false }
    if (showSettings) SettingsDialog(model) { showSettings = false }
}

@Composable
fun Wordmark(size: Int = 20) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size((size * 0.45).dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width((size * 0.4).dp))
        Text("realm", fontSize = size.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    }
}

/** Outlined fields are 56dp; buttons in the same form match them. */
val FormHeight = 56.dp

@Composable
fun Setup(model: Model) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isLink = "/s/" in url

    fun go() {
        if (url.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            val u = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
            val t = token.trim().ifBlank { null }
            error = model.check(u, t)
            busy = false
            if (error == null) model.connect(u, t)
        }
    }

    Column(
        Modifier.widthIn(max = 420.dp).fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Wordmark(34)
        Spacer(Modifier.height(8.dp))
        Text("Your dump, everywhere.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            url, { url = it },
            label = { Text("Server or share link") },
            placeholder = { Text("realm.you.workers.dev") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        Spacer(Modifier.height(12.dp))
        AnimatedVisibility(!isLink) {
            OutlinedTextField(
                token, { token = it },
                label = { Text("Token") },
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
        Button(onClick = ::go, enabled = !busy && url.isNotBlank(), modifier = Modifier.fillMaxWidth().height(FormHeight), shape = MaterialTheme.shapes.small) {
            Text(if (busy) "Connecting…" else "Connect")
        }
    }
}

@Composable
fun SectionDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
