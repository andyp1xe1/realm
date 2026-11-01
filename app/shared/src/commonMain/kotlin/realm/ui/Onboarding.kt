// Onboarding: logged in with Cloudflare, with no realm worker yet. What deploying sets up, each requirement
// checked as soon as the screen opens, where realm will answer, and Deploy, dimmed until all of it is there.
// What's checked shows with Loading: rows of the same height hold their room, then fade through in place.
package realm.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import realm.Account
import realm.D1_FREE_LIMIT
import realm.DatabaseCheck
import realm.DomainChoice
import realm.Home
import realm.Loaded
import realm.R2Check
import realm.R2NotEnabled
import realm.ReleaseCheck
import realm.Requirements
import realm.SubdomainCheck
import realm.isValid
import realm.validSubdomain
import realm.value
import realm.Onboarding as State

/**
 * The whole screen in place of setup, with settings in its corner for the account and signing out: choosing
 * an account, then, on one with no realm, deploying it. The one fades through to the other.
 */
@Composable
fun OnboardingScreen(home: Home, modifier: Modifier = Modifier) {
    val o = home.onboarding
    var settings by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Wordmark(size = 28)
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                o.account,
                // Choosing an account fades through to its checks, the screen fitting what comes.
                transitionSpec = {
                    (fadeIn(tween(STEP_MILLIS * 2 / 3, delayMillis = STEP_MILLIS / 3)) togetherWith fadeOut(tween(STEP_MILLIS / 3)))
                        .using(SizeTransform(clip = true))
                },
                contentKey = { it != null },
            ) { a -> Column { if (a == null) Chooser(home) else Checks(a, o) } }
        }
        IconButton(onClick = { settings = true }, Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Icon(Icons.Outlined.Settings, "Settings")
        }
    }
    if (settings) SettingsDialog(home, onDismiss = { settings = false })
}

private const val STEP_MILLIS = 300

/** Why checking or deploying failed, in the colour of a problem. */
@Composable
internal fun ColumnScope.Problem(text: String?) {
    if (text == null) return
    Spacer(Modifier.height(12.dp))
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

/** Deploy, dimmed until every requirement is met; Retry after a failed deploy; Sign in if realm is there already. */
@Composable
private fun DeployButton(o: State) {
    val signIn = o.requirements.state.value?.deployed == true
    Button(
        onClick = if (signIn) ({ o.signInToIt() }) else o::deploy,
        enabled = !o.busy && (signIn || o.deployment != null),
        modifier = Modifier.fillMaxWidth().height(FormHeight),
        shape = CircleShape,
    ) {
        Text(
            when {
                signIn && o.busy -> "Signing in…"
                signIn -> "Sign in"
                o.comingUp != null -> "Deployed"
                o.deployFailed -> "Retry"
                else -> "Deploy"
            },
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** What deploying sets up, the account, each requirement, Check again, where realm will answer, and Deploy. */
@Composable
private fun ColumnScope.Checks(account: Account, o: State) {
    val state = o.requirements.state
    Text("Deploy realm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "Deploying puts realm on your own Cloudflare account: a worker that runs it, an R2 bucket that keeps your " +
            "files, and a D1 database that keeps your items. Cloudflare's free plan is enough.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    AccountRow(account, o)
    Spacer(Modifier.height(4.dp))
    Label("What it needs")
    Loading(state, skeleton = { Column { RowsSkeleton() } }) { r ->
        Column { Rows(r, o, refreshing = (state as? Loaded.Known)?.refreshing == true) }
    }
    InlineButton("Check again", o::check)
    Spacer(Modifier.height(12.dp))
    Loading(state, skeleton = { Column { AddressSkeleton() } }) { r -> Column { if (!r.deployed) Address(r, o) } }
    o.comingUp?.let { ComingUp(it) }
    Problem(o.problem)
    Spacer(Modifier.height(20.dp))
    DeployButton(o)
}

/** Deployed, but its new address isn't answering yet: what's happening, while signing in is tried again. */
@Composable
private fun ColumnScope.ComingUp(origin: String) {
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "realm is deployed. ${origin.removePrefix("https://")} is coming up: a new address takes a few minutes. " +
                "You're signed in as soon as it answers.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Which Cloudflare account realm goes on, by name once the login's accounts are known, and using another. */
@Composable
private fun AccountRow(account: Account, o: State) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Label("Cloudflare account")
            Loading(o.accounts.state, skeleton = { SkeletonLine(0.6f, style = MaterialTheme.typography.bodyMedium) }) { list ->
                val name = list.firstOrNull { it.id == account.id }?.name ?: account.name
                Text(
                    name.ifBlank {
                        account.id
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = o::pickAnother, enabled = !o.busy) { Text("Use another") }
    }
}

/** How tall a requirement's row is, met or not, so checking never moves what's under it. */
private val RowHeight = 64.dp

/** Whether a requirement is met: [ok], or not, with [action] to fix it beside it; turning while it's checked again. */
@Composable
private fun Requirement(ok: Boolean, title: String, detail: String, refreshing: Boolean, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                refreshing -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                ok -> Icon(Icons.Outlined.Check, "Met", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                else -> Icon(Icons.Outlined.ErrorOutline, "Not met", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        action?.invoke()
    }
}

/** A requirement's row before it's checked. */
@Composable
private fun RequirementSkeleton(fraction: Float) {
    Row(Modifier.fillMaxWidth().height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            SkeletonLine(fraction, style = MaterialTheme.typography.bodyMedium)
            SkeletonLine(fraction * 0.7f, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RowsSkeleton() {
    listOf(0.45f, 0.6f, 0.55f, 0.4f).forEach { RequirementSkeleton(it) }
}

/** Opens a page of the Cloudflare dashboard, where only the user can fix this. */
@Composable
private fun Dashboard(text: String, url: String) {
    val uri = LocalUriHandler.current
    TextButton(onClick = { uri.openUri(url) }) { Text(text) }
}

@Composable
private fun Rows(r: Requirements, o: State, refreshing: Boolean) {
    if (r.deployed) {
        val name = r.account.name.ifBlank { r.account.id }
        return Requirement(true, "realm is on $name already", "Sign in to it to use it.", refreshing)
    }
    when (r.r2) {
        R2Check.On -> Requirement(true, "R2 is turned on", "Your files go in its bucket.", refreshing)

        R2Check.Off -> Requirement(
            false,
            "R2 isn't turned on for this account",
            "Turn it on in the dashboard, then check again.",
            refreshing,
        ) {
            Dashboard("Open R2", R2NotEnabled.URL)
        }
    }
    when (val s = r.subdomain) {
        is SubdomainCheck.Has -> Requirement(true, "workers.dev subdomain", "${s.name}.workers.dev", refreshing)

        SubdomainCheck.Missing -> {
            val typed = validSubdomain(o.subdomain)
            if (typed != null) {
                Requirement(true, "workers.dev subdomain", "$typed.workers.dev, given to your account as realm deploys.", refreshing)
            } else {
                Requirement(false, "No workers.dev subdomain", "Type one under Address, or open Workers in the dashboard.", refreshing) {
                    Dashboard("Open Workers", SubdomainCheck.Missing.URL)
                }
            }
        }
    }
    when (val d = r.database) {
        is DatabaseCheck.Reuses -> Requirement(true, "A D1 database", "realm uses the database ${d.name} that's there.", refreshing)

        is DatabaseCheck.Room -> Requirement(
            true,
            "Room for a D1 database",
            "${d.count} of the free plan's $D1_FREE_LIMIT used.",
            refreshing,
        )

        is DatabaseCheck.Full -> Requirement(
            false,
            "No room for a D1 database",
            "It has ${d.count}, the free plan's most. Delete one, then check again.",
            refreshing,
        ) { Dashboard("Open D1", DatabaseCheck.Full.URL) }
    }
    when (val rel = r.release) {
        is ReleaseCheck.Ready ->
            Requirement(true, "A release to deploy", "${rel.release.tag}, realm ${rel.release.manifest.version}", refreshing)

        is ReleaseCheck.NeedsNewerApp ->
            Requirement(false, "This app is too old to deploy", "Release ${rel.newer} needs a newer app. Update the app.", refreshing)

        ReleaseCheck.None -> Requirement(false, "No release to deploy", "realm has no release yet.", refreshing)

        is ReleaseCheck.Failed -> Requirement(false, "Can't find a release to deploy", rel.message, refreshing)
    }
}

/**
 * Where realm will answer: workers.dev, or a domain of the account's with a subdomain, realm unless changed
 * (blank is the domain itself). An account with no workers.dev subdomain picks one too: realm answers there
 * as well. Each shows the address it comes to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Address(r: Requirements, o: State) {
    val zone = o.zone
    val has = (r.subdomain as? SubdomainCheck.Has)?.name
    val sub = has ?: validSubdomain(o.subdomain)
    val workersDev = sub?.let { "realm.$it.workers.dev" }
    Label("Address")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = zone == null, onClick = { o.zone = null }, label = { Text("workers.dev") }, shape = CircleShape)
        r.zones.forEach { z -> FilterChip(selected = zone == z, onClick = { o.zone = z }, label = { Text(z.name) }, shape = CircleShape) }
    }
    Spacer(Modifier.height(8.dp))
    if (zone != null) {
        val choice = DomainChoice(zone, o.name)
        OutlinedTextField(
            o.name,
            { o.name = it },
            label = { Text("Subdomain") },
            supportingText = {
                Text(if (choice.isValid) "realm answers on ${choice.hostname}." else "Letters, digits and hyphens, or blank.")
            },
            isError = !choice.isValid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
    }
    if (has == null) {
        OutlinedTextField(
            o.subdomain,
            { o.subdomain = it },
            label = { Text("workers.dev subdomain") },
            supportingText = {
                Text(
                    when {
                        o.subdomain.isBlank() -> "Your account has none yet. Pick one, like your name."
                        workersDev == null -> "Letters, digits and hyphens only."
                        zone == null -> "realm answers on $workersDev."
                        else -> "realm answers on $workersDev too."
                    },
                )
            },
            isError = o.subdomain.isNotBlank() && workersDev == null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
    } else if (zone == null) {
        Text("realm answers on $workersDev.", style = MaterialTheme.typography.bodyMedium)
    } else {
        Text("And on $workersDev.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The room [Address] takes on an account with a workers.dev subdomain, before it's known. */
@Composable
private fun ColumnScope.AddressSkeleton() {
    Label("Address")
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
        SkeletonLine(0.55f, style = MaterialTheme.typography.labelLarge)
    }
    Spacer(Modifier.height(8.dp))
    SkeletonLine(0.6f, style = MaterialTheme.typography.bodyMedium)
}
