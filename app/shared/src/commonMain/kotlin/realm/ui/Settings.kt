package realm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import realm.Home
import realm.Login
import realm.Screen
import realm.screen

/** Widest settings gets. */
val SettingsWidth = 520.dp

/** The screen the app is on, with the settings page over it while it's open; under it, the screen keeps its place. */
@Composable
fun Screens(home: Home, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        SetupOrTabs(home)
        AnimatedVisibility(
            home.settings && home.screen != Screen.Setup,
            enter = fadeIn(tween(PAGE_MILLIS)) + slideInHorizontally(tween(PAGE_MILLIS)) { it / 8 },
            exit = fadeOut(tween(PAGE_MILLIS)) + slideOutHorizontally(tween(PAGE_MILLIS)) { it / 8 },
        ) { SettingsPage(home) }
    }
}

private const val PAGE_MILLIS = 250

/** Settings as a page: Back, or Escape, leaves it, and nothing it started stops. */
@Composable
fun SettingsPage(home: Home, modifier: Modifier = Modifier) {
    val close = { home.settings = false }
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = home.settings) { close() }
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).onPreviewKeyEvent { e ->
            (e.type == KeyEventType.KeyDown && e.key == Key.Escape).also { if (it) close() }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.widthIn(max = PageWidth).fillMaxWidth().height(TopBarHeight).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            BarTitle("Settings", Modifier.weight(1f), start = 0.dp)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Settings(
                home,
                Modifier.widthIn(max = SettingsWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).reportsHeight("settings"),
            )
        }
    }
}

@Composable
private fun Settings(home: Home, modifier: Modifier = Modifier) = Column(modifier) {
    val server = home.server
    UpdatesSection(home)
    Spacer(Modifier.height(12.dp))
    when {
        server != null -> {
            ServerSection(home)
            Spacer(Modifier.height(16.dp))
            if (home.session?.login?.account != null) InlineButton("Use another Cloudflare account", home::useAnotherAccount)
            InlineButton("Sign out", home::signOut, color = MaterialTheme.colorScheme.error)
        }

        home.onboarding.session != null -> CloudflareAccount(home)

        else -> {
            Text(
                "Sign in to your own server to make realms. Links you've added stay either way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            CloudflareButton(onClick = home.cloudflare::logIn, busy = home.cloudflare.login == Login.LoggingIn)
            Spacer(Modifier.height(20.dp))
            SignInForm(home, onDone = { home.settings = false })
        }
    }
}

@Composable
private fun CloudflareAccount(home: Home) = Column {
    val o = home.onboarding
    Text("Logged in to Cloudflare", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Loading(o.accounts.state, skeleton = { SkeletonLine(0.5f) }) { accounts ->
        val names = (o.account?.let(::listOf) ?: accounts).joinToString(", ") { it.name.ifBlank { it.id } }
        Text(names, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(4.dp))
    Text(
        (if (o.account != null) "realm isn't on this account yet. " else "No account chosen yet. ") +
            "Signing out ends this Cloudflare login; links you've added stay.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    if (o.account != null) {
        InlineButton("Use another Cloudflare account", {
            home.settings = false
            o.pickAnother()
        })
    }
    InlineButton("Sign out", home::signOut, color = MaterialTheme.colorScheme.error)
}
