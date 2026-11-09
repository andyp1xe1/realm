package realm.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import realm.AppUpdates
import realm.SelfUpdate

/** The wordmark with the app's version, and replacing the app with a newer release when this copy can. */
@Composable
fun AppSection(updates: AppUpdates, modifier: Modifier = Modifier) {
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
        if (version != null) {
            AnimatedContent(updates.state, transitionSpec = { swap() }, contentKey = { it::class }) { s ->
                Column(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalArrangement = Arrangement.Center) { Step(s, updates) }
            }
        }
    }
}

@Composable
private fun ColumnScope.Step(s: SelfUpdate, updates: AppUpdates) {
    when (s) {
        SelfUpdate.NotYet -> InlineButton("Check for app updates", updates::check)

        SelfUpdate.Checking -> Turning("Checking for app updates")

        SelfUpdate.UpToDate -> {
            Said("The app is up to date.")
            InlineButton("Check again", updates::check)
        }

        is SelfUpdate.Available -> {
            Said("App update: ${s.tag} is out.")
            InlineButton("Update the app", updates::install)
        }

        is SelfUpdate.Downloading -> {
            Said("Downloading ${s.tag}")
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator({ s.progress }, Modifier.fillMaxWidth())
        }

        is SelfUpdate.Installing -> Turning("Installing ${s.tag}")

        is SelfUpdate.Ready -> {
            Said("${s.tag} is installed. Restart to use it.")
            InlineButton("Restart", updates::restart)
        }

        is SelfUpdate.Failed -> {
            Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            InlineButton("Try again", if (s.retriesInstall) updates::install else updates::check)
        }
    }
}

@Composable
private fun Said(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)
