// The sheet for logging in with Cloudflare and deploying the worker: which account, what deploying would
// do, its progress, and DNS records in the way. Its content fades through from one step to the next.
package realm.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import realm.Choices
import realm.CloudflareSetup
import realm.DomainChoice
import realm.Existing
import realm.Login
import realm.Plan
import realm.R2NotEnabled
import realm.Resource
import realm.Step
import realm.Zone

/** Whatever logging in with Cloudflare or deploying is asking or doing, in one sheet; nothing while the browser is out. */
@Composable
fun CloudflareSheet(cloudflare: CloudflareSetup, modifier: Modifier = Modifier) {
    val login = cloudflare.login
    if (login == null || login == Login.LoggingIn) return
    Sheet(cloudflare::dismiss, modifier) {
        AnimatedContent(
            login,
            // Steps fade through, the sheet growing or shrinking to the next; a step that only moves on stays put.
            transitionSpec = {
                (fadeIn(tween(STEP_MILLIS * 2 / 3, delayMillis = STEP_MILLIS / 3)) togetherWith fadeOut(tween(STEP_MILLIS / 3)))
                    .using(SizeTransform(clip = true))
            },
            contentKey = { it::class },
        ) { l ->
            Column {
                when (l) {
                    is Login.PickWorker -> PickWorker(l)
                    is Login.PickAccount -> PickAccount(l, cloudflare)
                    is Login.Planning -> Planning(l)
                    is Login.Confirm -> if (l.plan is Plan.Ready) Confirm(l, cloudflare) else Refused(l, cloudflare)
                    is Login.Failed -> Failed(l, cloudflare)
                    is Login.Deploying -> Deploying(l)
                    is Login.Replace -> Replace(l)
                    Login.LoggingIn -> {}
                }
            }
        }
    }
}

private const val STEP_MILLIS = 300

@Composable
private fun ColumnScope.Title(text: String, body: String? = null) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (body != null) {
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Choice(title: String, detail: String?, onClick: () -> Unit) {
    TextButton(onClick = onClick, Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ColumnScope.Buttons(content: @Composable () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { content() }
}

@Composable
private fun ColumnScope.Problem(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ColumnScope.PickWorker(l: Login.PickWorker) {
    Title("Which realm?", "There's a realm worker on more than one of your Cloudflare accounts.")
    Spacer(Modifier.height(12.dp))
    l.workers.forEach { w -> Choice(w.accountName, w.origin.removePrefix("https://")) { l.pick(w) } }
}

@Composable
private fun ColumnScope.PickAccount(l: Login.PickAccount, cloudflare: CloudflareSetup) {
    Title("Deploy realm", "There's no realm worker on your Cloudflare accounts yet. Which one should get it?")
    Spacer(Modifier.height(12.dp))
    // Accounts go by their names; an id only tells apart two with the same one.
    l.accounts.forEach { a ->
        val title = a.name.ifBlank { a.id }
        val id = a.id.takeIf { title != a.id && l.accounts.count { it.name == a.name } > 1 }
        Choice(title, id) { cloudflare.deployTo(a) }
    }
}

/** The plan is being worked out: its place stays blank, under a thin bar, until it fades in. */
@Composable
private fun ColumnScope.Planning(l: Login.Planning) {
    Title(if (l.updating) "Update realm" else "Deploy realm")
    Spacer(Modifier.height(16.dp))
    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent)
    Spacer(Modifier.height(96.dp))
}

private fun Resource.words() = when (this) {
    is Resource.Script -> "the worker $name"
    is Resource.Bucket -> "the R2 bucket $name"
    is Resource.Database -> "the D1 database $name"
}

private fun List<Resource>.words() = map { it.words() }.let {
    if (it.size >
        1
    ) {
        it.dropLast(1).joinToString(", ") + " and " + it.last()
    } else {
        it.single()
    }
}

/** One line of the plan: what it's about, muted, then what. */
@Composable
internal fun Fact(label: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** What deploying or updating would do, where it would live, and Deploy. Nothing is written before that. */
@Composable
private fun ColumnScope.Confirm(l: Login.Confirm, cloudflare: CloudflareSetup) {
    val plan = l.plan
    val found = plan.found
    val account = found.account.name.ifBlank { found.account.id }
    val release = "${plan.release.tag}, realm ${plan.release.manifest.version}"
    if (plan is Plan.Update) {
        val at = (found.script as? Existing.Answered)?.origin?.removePrefix("https://")
        Title(
            "Update realm",
            "realm ${plan.from.version} → ${plan.release.manifest.version}${at?.let {
                " on $it"
            }.orEmpty()}. Its realms, items and links stay.",
        )
    } else {
        Title("Deploy realm", "There's no realm worker on $account yet. This puts one there.")
    }
    Spacer(Modifier.height(12.dp))
    Fact("Release", release)
    if (plan.create.isNotEmpty()) Fact("New", plan.create.words())
    if (plan.reuse.isNotEmpty()) Fact(if (plan is Plan.Update) "Kept" else "Already there", plan.reuse.words())

    var choices by remember(l) { mutableStateOf(l.choices) }
    if (plan is Plan.Fresh) {
        Spacer(Modifier.height(12.dp))
        Where(found.subdomain, found.zones, choices) { choices = it }
    }

    l.problem?.let { p ->
        if (p is R2NotEnabled) {
            Problem("R2 is off on $account. Turn it on in the Cloudflare dashboard, then deploy again.")
            val uri = LocalUriHandler.current
            TextButton(onClick = { uri.openUri(R2NotEnabled.URL) }) { Text("Open the dashboard") }
        } else {
            Problem(p.message.orEmpty())
        }
    }
    val ready = found.subdomain != null || !choices.subdomain.isNullOrBlank()
    Buttons {
        TextButton(onClick = cloudflare::dismiss) { Text("Cancel") }
        Button(onClick = { cloudflare.deploy(choices) }, enabled = ready, shape = MaterialTheme.shapes.small) {
            Text(if (plan is Plan.Update) "Update" else "Deploy")
        }
    }
}

/**
 * Where a new worker lives: workers.dev, or a domain of the account's with a subdomain, realm unless changed
 * (blank is the domain itself). An account with no workers.dev subdomain picks one first; the worker answers there too.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Where(subdomain: String?, zones: List<Zone>, choices: Choices, onChange: (Choices) -> Unit) {
    val domain = choices.domain
    var name by remember { mutableStateOf(domain?.name ?: "realm") }
    // The zone last picked, so the subdomain field keeps its words while it closes.
    var zone by remember { mutableStateOf(domain?.zone) }
    val sub = subdomain ?: choices.subdomain?.trim()?.lowercase()?.ifEmpty { null }
    val workersDev = sub?.let { "realm.$it.workers.dev" }
    Text("Address", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    if (zones.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = domain == null, onClick = {
                onChange(choices.copy(domain = null))
            }, label = { Text("workers.dev") }, shape = CircleShape)
            zones.forEach { z ->
                FilterChip(
                    selected = domain?.zone == z,
                    onClick = {
                        zone = z
                        onChange(choices.copy(domain = DomainChoice(z, name)))
                    },
                    label = { Text(z.name) },
                    shape = CircleShape,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    // The subdomain opens out under the chips, as the sheet grows to fit it.
    AnimatedVisibility(
        domain != null,
        enter = opening(),
        exit = fadeOut(tween(STEP_MILLIS)) + shrinkVertically(tween(STEP_MILLIS), shrinkTowards = Alignment.Top),
    ) {
        val shown = DomainChoice(zone ?: return@AnimatedVisibility, name)
        OutlinedTextField(
            name,
            {
                name = it
                onChange(choices.copy(domain = shown.copy(name = it)))
            },
            label = { Text("Subdomain") },
            supportingText = { Text(shown.hostname) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
    }
    if (subdomain == null) {
        OutlinedTextField(
            choices.subdomain.orEmpty(),
            { onChange(choices.copy(subdomain = it)) },
            label = { Text("workers.dev subdomain") },
            supportingText = {
                Text(
                    workersDev?.let {
                        "The worker answers on $it."
                    } ?: "Your account has none yet: pick one for its workers.dev addresses.",
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
    } else {
        val dev = "realm.$subdomain.workers.dev"
        if (domain == null) {
            Text(dev, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("Also on $dev", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Deploying would overwrite or downgrade what's there, or can't tell: why, and nothing to do but close. */
@Composable
private fun ColumnScope.Refused(l: Login.Confirm, cloudflare: CloudflareSetup) {
    val reason = (l.plan as Plan.Refused).reason
    Title(if (l.plan.found.script != null) "Update realm" else "Deploy realm")
    Spacer(Modifier.height(8.dp))
    Text(reason.message, style = MaterialTheme.typography.bodyMedium)
    Buttons { TextButton(onClick = cloudflare::dismiss) { Text("Close") } }
}

@Composable
private fun ColumnScope.Failed(l: Login.Failed, cloudflare: CloudflareSetup) {
    Title("Can't deploy realm")
    Spacer(Modifier.height(8.dp))
    Text(l.message, style = MaterialTheme.typography.bodyMedium)
    Buttons { TextButton(onClick = cloudflare::dismiss) { Text("Close") } }
}

private fun Step.words(): String = when (this) {
    is Step.Subdomain -> "Giving your account $name.workers.dev"
    is Step.Bucket -> "Setting up the R2 bucket $name"
    is Step.Database -> "Setting up the D1 database $name"
    is Step.Upload -> "Uploading the worker from release $tag"
    Step.Schedule -> "Scheduling its daily cleanup"
    is Step.WorkersDev -> "Turning on ${origin.removePrefix("https://")}"
    is Step.Domain -> "Putting it on $hostname"
    is Step.Waiting -> "Waiting for ${origin.removePrefix("https://")} to answer"
}

/** Each step as it starts, fading in under the last: done ones ticked, the current one turning. */
@Composable
private fun ColumnScope.Deploying(l: Login.Deploying) {
    Title(if (l.plan is Plan.Update) "Updating realm" else "Deploying realm")
    Spacer(Modifier.height(12.dp))
    l.steps.forEachIndexed { i, step ->
        key(i) {
            val shown = remember { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(
                shown,
                enter = opening(),
            ) {
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        if (i < l.steps.lastIndex) {
                            Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        } else {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    val done = i < l.steps.lastIndex
                    Text(
                        step.words(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** DNS records stand in the way of the custom domain: shown, and deleted only if the user says so. */
@Composable
private fun ColumnScope.Replace(l: Login.Replace) {
    val q = l.question
    val hostname = q.conflict.choice.hostname
    Title(
        "Replace DNS records?",
        "$hostname has DNS records already, and Cloudflare won't put realm there while they're in the way.",
    )
    Spacer(Modifier.height(12.dp))
    q.conflict.records.forEach { r ->
        Text("${r.type}  ${r.name}  ${r.content}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Deleting them stops whatever they point to now. realm answers on ${q.origin.removePrefix("https://")} either way.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    q.problem?.let { Problem(it.message.orEmpty()) }
    Buttons {
        TextButton(onClick = { l.answer(false) }) { Text("Keep workers.dev") }
        TextButton(onClick = { l.answer(true) }) { Text("Delete them", color = MaterialTheme.colorScheme.error) }
    }
}

/** Fading in and opening out downward, the sheet growing to fit: for a deploy's next step, and the field a pick opens. */
private fun opening() = fadeIn(tween(STEP_MILLIS)) +
    expandVertically(tween(STEP_MILLIS), Alignment.Top) // grows: deploy steps, subdomain field (approved by PENDING, until 2026-11-15)
