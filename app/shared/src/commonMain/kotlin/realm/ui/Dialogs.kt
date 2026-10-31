package realm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import realm.Home
import realm.Login

@Composable
internal fun Sheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = modifier.widthIn(max = 520.dp),
        ) {
            Column(Modifier.padding(20.dp)) { content() }
        }
    }
}

/** Asks before something that can't be taken back. [action] names the button: "Delete", "Revoke", "Remove". */
@Composable
fun Confirm(title: String, body: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit, modifier: Modifier = Modifier) =
    Sheet(onDismiss, modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(action, color = MaterialTheme.colorScheme.error) }
        }
    }

@Composable
fun SettingsDialog(home: Home, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val server = home.server
    Sheet(onDismiss, modifier) {
        Wordmark()
        Spacer(Modifier.height(16.dp))
        if (server != null) {
            ServerSection(home, onClose = onDismiss)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    home.signOut()
                    onDismiss()
                }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        } else {
            Text(
                "Sign in to your own server to make realms. Links you've added stay either way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            CloudflareButton(onClick = home.cloudflare::logIn, busy = home.cloudflare.login == Login.LoggingIn)
            Spacer(Modifier.height(20.dp))
            SignInForm(home, onDone = onDismiss)
        }
    }
}
