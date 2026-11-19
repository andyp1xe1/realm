package realm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import realm.AppUpdates
import realm.Home
import realm.SelfUpdate
import realm.UpdatesView
import realm.Updating

/**
 * The wordmark with the app's version, and what's out for this device's app and the server signed in to, updated
 * at one ask. It keeps one height whatever it shows.
 */
@Composable
fun UpdatesSection(home: Home, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val updates = home.appUpdates
    val updating = home.updating
    LaunchedEffect(updates) { updates.refresh() }
    val version = updates.version
    Column(modifier) {
        Row {
            Wordmark(Modifier.alignByBaseline())
            if (version != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "v$version",
                    Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Steps(updating.view) { v ->
            Column {
                Column(Modifier.fillMaxWidth().heightIn(min = Lines), verticalArrangement = Arrangement.Center) { Said(v) }
                Box(Modifier.fillMaxWidth().heightIn(min = ButtonRow), contentAlignment = Alignment.CenterStart) {
                    Action(v, updating, updates, onClose)
                }
            }
        }
    }
}

private val Lines = 52.dp
private val ButtonRow = 48.dp

@Composable
private fun ColumnScope.Said(v: UpdatesView) {
    when (v) {
        UpdatesView.NotYet -> {}

        UpdatesView.Loading -> Delayed {
            Column {
                SkeletonLine(0.6f, style = MaterialTheme.typography.bodyMedium)
                SkeletonLine(0.5f, style = MaterialTheme.typography.bodyMedium)
            }
        }

        UpdatesView.Checking -> Turning("Checking for updates")

        is UpdatesView.Out -> {
            if (v.app != null) Fact("App", (v.appFrom?.let { "v$it → " }.orEmpty()) + "${v.app}")
            if (v.server != null) Fact("Server", (v.serverFrom?.let { "$it → " }.orEmpty()) + "${v.server}")
        }

        is UpdatesView.App -> when (val w = v.work) {
            SelfUpdate.Checking -> Turning("Checking for updates")

            is SelfUpdate.Downloading -> {
                Line("Downloading ${w.tag}")
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator({ w.progress }, Modifier.fillMaxWidth())
            }

            is SelfUpdate.Installing -> Turning("Installing ${w.tag}")

            is SelfUpdate.Ready -> Line("${w.tag} is installed. Restart to use it.")

            is SelfUpdate.Failed -> Text(w.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        is UpdatesView.Held -> Line(v.reason)

        is UpdatesView.Failed -> Text(v.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

        UpdatesView.Current -> Line("Up to date.")
    }
}

@Composable
private fun Action(v: UpdatesView, updating: Updating, updates: AppUpdates, onClose: () -> Unit) {
    when (v) {
        UpdatesView.NotYet -> InlineButton("Check for updates", updating::check)

        UpdatesView.Loading -> Delayed { SkeletonLine(0.25f, style = MaterialTheme.typography.labelLarge) }

        UpdatesView.Checking -> {}

        is UpdatesView.Out -> InlineButton("Update", {
            if (v.app == null) onClose()
            updating.start()
        })

        is UpdatesView.App -> when (val w = v.work) {
            is SelfUpdate.Ready -> InlineButton("Restart", updates::restart)
            is SelfUpdate.Failed -> InlineButton("Try again", if (w.retriesInstall) updating::start else updating::check)
            else -> {}
        }

        is UpdatesView.Held, is UpdatesView.Failed, UpdatesView.Current -> InlineButton("Check again", updating::check)
    }
}

@Composable
private fun Line(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)
