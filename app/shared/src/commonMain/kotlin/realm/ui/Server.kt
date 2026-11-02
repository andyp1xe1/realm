// The server you're signed in to, in settings: what it runs, checking for a newer one, and, signed in with
// Cloudflare, the addresses it answers on. What's fetched shows with Loading; each change fades through in place.
package realm.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import realm.AddressTask
import realm.Addresses
import realm.Check
import realm.DomainChoice
import realm.Home
import realm.ServerFacts
import realm.ServerSettings
import realm.UpdateCheck
import realm.Zone

/** How long a part of the section takes to fade through to what it shows next. */
private const val SWAP_MILLIS = 300

/** Fades through, the section growing or shrinking to fit; a change that keeps the same kind of content stays put. */
private fun AnimatedContentTransitionScope<*>.swap(): ContentTransform =
    (fadeIn(tween(SWAP_MILLIS * 2 / 3, delayMillis = SWAP_MILLIS / 3)) togetherWith fadeOut(tween(SWAP_MILLIS / 3)))
        .using(SizeTransform(clip = true))

/**
 * Settings' part about your own server: the address the app uses, then what it runs and, signed in with
 * Cloudflare, its updates and addresses: known already, fetched with the app, or a skeleton until they are.
 * [onClose] puts settings away, before a sheet of its own opens.
 */
@Composable
fun ServerSection(home: Home, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val settings = home.serverSettings
    val server = home.server ?: return
    LaunchedEffect(server) { settings.refresh() } // in place: what's known stays meanwhile
    // Signed in with Cloudflare, the section has addresses: the skeleton holds room for them.
    val managed = home.session?.login?.account != null
    Column(modifier) {
        Text("Signed in to", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(server.base.removeSuffix("/api"), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Loading(settings.facts.state, skeleton = { DetailsSkeleton(managed) }) { facts -> Column { Details(facts, home, onClose) } }
    }
}

/** The room [Details] takes before anything's known: what it runs, the newest release, and one address. */
@Composable
private fun DetailsSkeleton(managed: Boolean) = Column {
    Spacer(Modifier.height(12.dp))
    FactSkeleton("Runs")
    FactSkeleton("Newest")
    InlineButtonSkeleton(0.4f)
    if (managed) {
        Spacer(Modifier.height(12.dp))
        Text("Addresses", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            SkeletonLine(0.6f)
        }
        InlineButtonSkeleton(0.3f)
    }
}

/** [Fact] before its text is known. */
@Composable
private fun FactSkeleton(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SkeletonLine(0.35f, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ColumnScope.Details(facts: ServerFacts, home: Home, onClose: () -> Unit) {
    val settings = home.serverSettings
    val addresses = facts.addresses
    val checked = (settings.check as? Check.Done)?.result
    val release = when (checked) {
        is UpdateCheck.UpToDate -> checked.release.tag.toString()
        is UpdateCheck.Available -> checked.plan.release.tag.toString()
        is UpdateCheck.Refused -> checked.plan.release.tag.toString()
        is UpdateCheck.NeedsNewerApp -> checked.newer.toString()
        null -> home.cloudflare.knownRelease
    }
    Spacer(Modifier.height(12.dp))
    Fact("Runs", "${facts.info.fork ?: "realm"} ${facts.info.version}")
    if (release != null) Fact("Newest", release)
    val notice = home.cloudflare.update
    if (addresses == null) {
        if (notice != null) Text("Server update: realm ${notice.from} → ${notice.to}", style = MaterialTheme.typography.bodyMedium)
        InlineButton(if (notice != null) "Log in with Cloudflare to update" else "Log in with Cloudflare to manage this server", {
            onClose()
            home.cloudflare.logIn()
        })
        return
    }
    Updates(settings.check, home, onClose)
    Spacer(Modifier.height(12.dp))
    Text("Addresses", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Addresses(facts.origin, addresses, settings)
}

/** A small turning circle beside what's under way; the words say what. */
@Composable
private fun Turning(text: String) {
    Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Check for updates, and what it found, in place of the button. */
@Composable
private fun Updates(check: Check, home: Home, onClose: () -> Unit) {
    AnimatedContent(check, transitionSpec = { swap() }, contentKey = { it::class }) { c ->
        Column {
            when (c) {
                Check.NotYet -> {
                    // The quiet notice from the daily check, until the button asks again.
                    home.cloudflare.update?.let { u ->
                        Text("Server update: realm ${u.from} → ${u.to}", style = MaterialTheme.typography.bodyMedium)
                    }
                    InlineButton("Check for updates", home.serverSettings::checkForUpdates)
                }

                Check.Checking -> Turning("Checking for updates")

                is Check.Failed -> {
                    Text(c.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    InlineButton("Check again", home.serverSettings::checkForUpdates)
                }

                is Check.Done -> Found(c.result, home, onClose)
            }
        }
    }
}

@Composable
private fun ColumnScope.Found(result: UpdateCheck, home: Home, onClose: () -> Unit) {
    val text = when (result) {
        is UpdateCheck.UpToDate -> "Up to date."
        is UpdateCheck.Available -> "Update available: realm ${result.plan.from.version} → ${result.plan.release.manifest.version}."
        is UpdateCheck.Refused -> result.plan.reason.message
        is UpdateCheck.NeedsNewerApp -> "Release ${result.newer} needs a newer app. Update the app to deploy it."
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.heightIn(min = 24.dp))
    if (result is UpdateCheck.Available) {
        InlineButton("Update", {
            onClose()
            home.cloudflare.startUpdate()
        })
    } else {
        InlineButton("Check again", home.serverSettings::checkForUpdates)
    }
}

/**
 * Each address the worker answers on, the one in use marked, and workers.dev while it's off; then adding
 * one, or what's being done. The last address it answers on can't be taken away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Addresses(inUse: String, a: Addresses, settings: ServerSettings) {
    val task = settings.task
    // Nothing to do to an address while another change is under way.
    val busy = task != null && task != AddressTask.Adding && task !is AddressTask.Failed
    (listOfNotNull(a.workersDev) + a.origins).distinct().forEach { origin ->
        val on = origin in a.origins
        // The address fills its line; on a narrow screen the actions wrap under it rather than cut it short.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                origin.removePrefix("https://"),
                Modifier.weight(1f).heightIn(min = 40.dp).wrapContentHeight(),
                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (origin == inUse) {
                    Text("In use", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                if (!busy) {
                    if (!on) TextButton(onClick = settings::turnOnWorkersDev) { Text("Turn on") }
                    if (on && origin != inUse) TextButton(onClick = { settings.use(origin) }) { Text("Use") }
                    if (on && a.origins.size > 1) {
                        TextButton(onClick = { settings.remove(origin) }) {
                            Text(if (origin == a.workersDev) "Turn off" else "Remove", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    AnimatedContent(task, transitionSpec = { swap() }, contentKey = { it?.let { t -> t::class } }) { t ->
        Column {
            when (t) {
                null -> if (a.zones.isNotEmpty()) InlineButton("Add a domain", { settings.adding(true) })

                AddressTask.Adding -> AddDomain(a.zones, settings)

                is AddressTask.Attaching -> Turning("Putting realm on ${t.hostname}")

                is AddressTask.Conflict -> {
                    Text(
                        "${t.conflict.choice.hostname} has DNS records already. Deleting them stops whatever they point to now:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    t.conflict.records.forEach { r ->
                        Text(
                            "${r.type}  ${r.name}  ${r.content}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Answer("Delete them", onNo = { settings.replace(false) }, onYes = { settings.replace(true) })
                }

                is AddressTask.Removing -> Removing(t, workersDev = t.origin == a.workersDev, settings)

                is AddressTask.Switching -> Turning("Checking that realm answers on ${t.origin.removePrefix("https://")}")

                is AddressTask.Failed -> {
                    Text(t.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    InlineButton("OK", { settings.adding(false) })
                }
            }
        }
    }
}

/** Asking before the worker comes off an address, then saying so while it does. */
@Composable
private fun Removing(t: AddressTask.Removing, workersDev: Boolean, settings: ServerSettings) {
    val host = t.origin.removePrefix("https://")
    val moveTo = t.moveTo?.removePrefix("https://")
    when {
        t.confirmed && moveTo != null -> Turning("Moving to $moveTo, then taking realm off $host")

        t.confirmed -> Turning("Taking realm off $host")

        else -> Column {
            val ask = if (workersDev) "Turn off $host?" else "Take realm off $host?"
            val move = moveTo?.let { " The app moves to $it first." }.orEmpty()
            Text("$ask$move Share links made with that address stop working.", style = MaterialTheme.typography.bodyMedium)
            val yes = if (workersDev) "Turn off" else "Remove"
            Answer(yes, onNo = { settings.confirmRemove(false) }, onYes = { settings.confirmRemove(true) })
        }
    }
}

/** Cancel, and [yes] in the colour of something that can't be taken back. */
@Composable
private fun Answer(yes: String, onNo: () -> Unit, onYes: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onNo) { Text("Cancel") }
        TextButton(onClick = onYes) { Text(yes, color = MaterialTheme.colorScheme.error) }
    }
}

/** A domain of the account's and a subdomain, realm unless changed; blank is the domain itself. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.AddDomain(zones: List<Zone>, settings: ServerSettings) {
    var zone by remember { mutableStateOf(zones.first()) }
    var name by remember { mutableStateOf("realm") }
    val choice = DomainChoice(zone, name)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        zones.forEach { z -> FilterChip(selected = z == zone, onClick = { zone = z }, label = { Text(z.name) }, shape = CircleShape) }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        name,
        { name = it },
        label = { Text("Subdomain") },
        supportingText = { Text(choice.hostname) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Row {
            TextButton(onClick = { settings.adding(false) }) { Text("Cancel") }
            TextButton(onClick = { settings.addDomain(choice) }) { Text("Add") }
        }
    }
}
