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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import realm.Api
import realm.AppUpdates
import realm.Home
import realm.Info
import realm.Loaded
import realm.Login
import realm.Phase
import realm.SelfUpdate
import realm.UpdatesView
import realm.Updating

/**
 * The versions this device's app and the server signed in to run, each with the newer one that's out, and one row
 * that says how updating both stands, with what to do next; while the server's update runs or asks something, that.
 * Each part keeps one height whatever it shows.
 */
@Composable
fun UpdatesSection(home: Home, modifier: Modifier = Modifier) = Column(modifier) {
    val updates = home.appUpdates
    val updating = home.updating
    LaunchedEffect(updates) { updates.refresh() }
    val due = updating.due
    SectionLabel("Versions")
    LoadingFact("App", Loaded.Known(updates.version?.let { "v$it" + due.app?.let { new -> " → $new" }.orEmpty() } ?: "built from source"))
    (home.phase as? Phase.SignedIn)?.let { p ->
        val runs = when (val s = p.facts.state) {
            is Loaded.Known -> Loaded.Known(s.value.info.runs() + due.server?.let { " → $it" }.orEmpty())
            Loaded.Unknown -> Loaded.Unknown
        }
        LoadingFact("Server", runs)
    }
    val working = home.cloudflare.login.let { it != null && it != Login.LoggingIn && home.phase is Phase.SignedIn }
    Steps(working, step = { it }) { w ->
        if (w) {
            Column(Modifier.padding(vertical = 8.dp)) { CloudflareWork(home.cloudflare) }
        } else {
            Steps(updating.view) { v ->
                Row(Modifier.fillMaxWidth().heightIn(min = StatusRow), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Said(v) }
                    Action(v, updating, updates)
                }
            }
        }
    }
}

private val StatusRow = 48.dp

private fun Info.runs(): String = if (fork != Api.FORK) running else release?.let { "v$it" } ?: "an older release"

@Composable
internal fun LoadingFact(label: String, value: Loaded<String>) {
    Row(Modifier.fillMaxWidth().height(FactRow), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Loading(value, skeleton = { SkeletonLine(0.35f, style = MaterialTheme.typography.bodyMedium) }) { v ->
            Changing(v) { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private val FactRow = 26.dp

@Composable
private fun Said(v: UpdatesView) {
    when (v) {
        UpdatesView.NotYet -> {}

        UpdatesView.Loading -> Delayed { SkeletonLine(0.4f, style = MaterialTheme.typography.bodyMedium) }

        UpdatesView.Checking -> Turning("Checking for updates")

        is UpdatesView.Out -> Line(if (v.app != null && v.server != null) "Newer versions are out." else "A newer version is out.")

        is UpdatesView.App -> when (val w = v.work) {
            SelfUpdate.Checking -> Turning("Checking for updates")

            is SelfUpdate.Downloading -> Column(Modifier.padding(end = 16.dp)) {
                Line("Downloading ${w.tag}")
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator({ w.progress }, Modifier.fillMaxWidth())
            }

            is SelfUpdate.Installing -> Turning("Installing ${w.tag}")

            is SelfUpdate.Ready -> Line("${w.tag} is installed.")

            is SelfUpdate.Failed -> Text(w.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        is UpdatesView.Held -> Line(v.reason)

        is UpdatesView.Limited -> Line(v.reason)

        is UpdatesView.Failed -> Text(v.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

        UpdatesView.Current -> Line("Up to date.")
    }
}

@Composable
private fun Action(v: UpdatesView, updating: Updating, updates: AppUpdates) {
    when (v) {
        UpdatesView.NotYet -> EndButton("Check for updates", updating::check)

        UpdatesView.Loading, UpdatesView.Checking, is UpdatesView.Limited -> {}

        is UpdatesView.Out -> EndButton("Update", updating::start)

        is UpdatesView.App -> when (val w = v.work) {
            is SelfUpdate.Ready -> EndButton("Restart", updates::restart)
            is SelfUpdate.Failed -> EndButton("Try again", if (w.retriesInstall) updating::start else updating::check)
            else -> {}
        }

        is UpdatesView.Held, is UpdatesView.Failed, UpdatesView.Current -> EndButton("Check again", updating::check)
    }
}

@Composable
private fun Line(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)
