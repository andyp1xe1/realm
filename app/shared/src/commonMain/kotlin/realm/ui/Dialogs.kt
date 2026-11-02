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
import androidx.compose.ui.text.style.TextOverflow
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
            // Signed in with Cloudflare: the chooser, with the same login.
            if (home.session?.login?.account != null) {
                InlineButton("Use another Cloudflare account", {
                    onDismiss()
                    home.useAnotherAccount()
                })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InlineButton("Sign out", {
                    home.signOut()
                    onDismiss()
                }, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        } else if (home.onboarding.session != null) {
            CloudflareAccount(home, onDismiss)
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

/** Logged in with Cloudflare with nothing deployed yet: which account, and signing out, which ends the login. */
@Composable
private fun CloudflareAccount(home: Home, onDismiss: () -> Unit) = Column {
    val o = home.onboarding
    Text("Logged in to Cloudflare", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Loading(o.accounts.state, skeleton = { SkeletonLine(0.5f) }) { accounts ->
        // The account chosen, else the ones the login was given, by name.
        val chosen = o.account?.let { a -> accounts.firstOrNull { it.id == a.id } ?: a }
        val names = (chosen?.let(::listOf) ?: accounts).joinToString(", ") { it.name.ifBlank { it.id } }
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
            onDismiss()
            o.pickAnother()
        })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        InlineButton("Sign out", {
            home.signOut()
            onDismiss()
        }, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onDismiss) { Text("Close") }
    }
}
