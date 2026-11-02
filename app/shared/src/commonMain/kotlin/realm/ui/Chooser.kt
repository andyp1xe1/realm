package realm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import realm.Account
import realm.Home
import realm.Loaded
import realm.Login
import realm.RealmOn
import realm.Onboarding as State

@Composable
internal fun ColumnScope.Chooser(home: Home) {
    val o = home.onboarding
    Text("Choose an account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "Your Cloudflare login can use these accounts. Choose the one realm runs on, or the one to deploy it to.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Loading(o.accounts.state, skeleton = { Column { repeat(2) { AccountSkeleton() } } }) { accounts ->
        Column {
            o.ordered(accounts).forEach { a ->
                val clash = accounts.count { it.name == a.name } > 1
                AccountChoice(a, clash, o)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    InlineButton(
        if (home.cloudflare.login == Login.LoggingIn) "Logging in…" else "Log in again to add accounts",
        home.cloudflare::logIn,
    )
    Problem(o.problem)
}

private val ChoiceHeight = 64.dp

@Composable
private fun AccountChoice(a: Account, clash: Boolean, o: State) {
    val realm = o.realms[a.id]?.state ?: Loaded.Unknown
    val title = a.name.ifBlank { a.id }
    TextButton(onClick = { o.choose(a) }, Modifier.fillMaxWidth().height(ChoiceHeight), enabled = realm is Loaded.Known && !o.busy) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (clash && title != a.id) "$title · ${a.id}" else title,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Loading(realm, skeleton = { SkeletonLine(0.45f, style = MaterialTheme.typography.bodySmall) }) { on ->
                    Text(
                        on.words(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.run { if (on is RealmOn.Running) primary else onSurfaceVariant },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (o.wasLast(a)) {
                Spacer(Modifier.width(8.dp))
                Text("Last used", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun RealmOn.words() = when (this) {
    is RealmOn.Running -> "realm runs here: ${worker.origin.removePrefix("https://")}"
    RealmOn.None -> "No realm yet: deploy it here"
    is RealmOn.NoAddress -> why
}

@Composable
private fun AccountSkeleton() {
    Column(Modifier.fillMaxWidth().height(ChoiceHeight).padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
        SkeletonLine(0.4f, style = MaterialTheme.typography.labelLarge)
        SkeletonLine(0.3f, style = MaterialTheme.typography.bodySmall)
    }
}
