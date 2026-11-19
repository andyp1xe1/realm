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
import realm.Phase
import realm.ServerFacts
import realm.ServerSettings
import realm.ServerStatus
import realm.Zone
import realm.serverStatus

/** Settings' part about your own server. [onClose] puts settings away before a sheet of its own opens. */
@Composable
fun ServerSection(home: Home, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val settings = home.serverSettings
    val signedIn = home.phase as? Phase.SignedIn ?: return
    val server = signedIn.server
    LaunchedEffect(server) { settings.refresh() }
    val managed = signedIn.session?.login?.account != null
    Column(modifier) {
        Text("Signed in to", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(server.base.removeSuffix("/api"), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Loading(signedIn.facts.state, skeleton = { DetailsSkeleton(managed) }) { facts -> Column { Details(facts, home, onClose) } }
    }
}

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
    val releases = home.releases.known
    Spacer(Modifier.height(12.dp))
    Fact("Runs", facts.info.running)
    releases?.newest?.let { Fact("Newest", "${it.tag}") }
    Updates(
        UpdatesView(settings.check, serverStatus(facts.info, releases), addresses != null, home.appUpdates.version != null),
        home,
        onClose,
    )
    if (addresses == null) return
    Spacer(Modifier.height(12.dp))
    Text("Addresses", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Addresses(facts.origin, addresses, settings)
}

/** What the updates part shows, as one value: [managed] with a Cloudflare login, and whether the app [updatesItself]. */
private data class UpdatesView(val check: Check, val status: ServerStatus?, val managed: Boolean, val updatesItself: Boolean)

@Composable
internal fun Turning(text: String) {
    Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Updates(view: UpdatesView, home: Home, onClose: () -> Unit) {
    Steps(view) { v ->
        Column {
            when (v.check) {
                Check.Checking -> Turning("Checking for updates")

                is Check.Failed -> {
                    Text(v.check.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    InlineButton("Check again", home.serverSettings::checkForUpdates)
                }

                Check.Idle -> Status(v, home, onClose)
            }
        }
    }
}

@Composable
private fun ColumnScope.Status(v: UpdatesView, home: Home, onClose: () -> Unit) {
    val app = if (v.updatesItself) "Update the app above first." else "Install a newer app first."
    val text = when (val s = v.status) {
        null -> null
        ServerStatus.Current -> "Up to date."
        is ServerStatus.Behind -> "Update available: ${s.to}."
        is ServerStatus.AppFirst -> "Release ${s.newest} needs a newer app. $app"
        ServerStatus.NewerThanApp -> "It's newer than this app. $app"
        is ServerStatus.OtherFork -> "This app doesn't update ${s.fork ?: "realm"}."
    }
    if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.heightIn(min = 24.dp))
    val manage = {
        onClose()
        home.cloudflare.manage()
    }
    when {
        v.status is ServerStatus.Behind && v.managed -> InlineButton("Update", {
            onClose()
            home.cloudflare.startUpdate()
        })

        v.status is ServerStatus.Behind -> InlineButton("Log in with Cloudflare to update", manage)

        else -> {
            InlineButton(if (v.status == null) "Check for updates" else "Check again", home.serverSettings::checkForUpdates)
            if (!v.managed) InlineButton("Log in with Cloudflare to manage this server", manage)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Addresses(inUse: String, a: Addresses, settings: ServerSettings) {
    val task = settings.task
    val busy = task != null && task != AddressTask.Adding && task !is AddressTask.Failed
    (listOfNotNull(a.workersDev) + a.origins).distinct().forEach { origin ->
        val on = origin in a.origins
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
    Steps(task) { t ->
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

@Composable
private fun Answer(yes: String, onNo: () -> Unit, onYes: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onNo) { Text("Cancel") }
        TextButton(onClick = onYes) { Text(yes, color = MaterialTheme.colorScheme.error) }
    }
}

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
