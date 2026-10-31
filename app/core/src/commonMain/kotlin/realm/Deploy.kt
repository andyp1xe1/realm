// Deploying a release with the user's Cloudflare login: storage first, then the worker, its schedule and its
// addresses, then waiting until it answers its owner. Every step can run again, so an update is the same run.
package realm

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What the user picked in the confirm dialog. */
data class Choices(
    /** The workers.dev subdomain to give an account that has none yet ([Found.subdomain] is null). */
    val subdomain: String? = null,
    /** A custom domain to put the worker on, besides workers.dev. */
    val domain: DomainChoice? = null,
)

/** What deploying is doing now, to show while it runs. */
sealed interface Step {
    /** Giving the account its workers.dev subdomain. */
    data class Subdomain(val name: String) : Step

    data class Bucket(val name: String) : Step

    data class Database(val name: String) : Step

    data class Upload(val tag: Semver) : Step

    /** Setting the worker's cron schedule, for its daily cleanup. */
    data object Schedule : Step

    /** Turning on the worker's workers.dev address. */
    data class WorkersDev(val origin: String) : Step

    data class Domain(val hostname: String) : Step

    /** Waiting for the worker to answer at [origin] and take this login as its owner. */
    data class Waiting(val origin: String) : Step
}

/** A finished deploy. */
data class Deployed(
    val account: Account,
    /** Where the worker answers: its custom domain if it has one, else workers.dev. Connect to this. */
    val origin: String,
    /**
     * What the worker said at [origin] to its owner. Null when it didn't answer in time: it's deployed, but the
     * address may take a few minutes to reach you, as new domains often do.
     */
    val info: Info?,
    /** What came of [Choices.domain], if one was picked. A conflict leaves the worker on workers.dev for now. */
    val domain: DomainOutcome?,
)

/** R2 is off on the account, and only the user can turn it on. */
class R2NotEnabled : ApiException("Turn on R2 in the Cloudflare dashboard, then try again: $URL") {
    companion object {
        const val URL = "https://dash.cloudflare.com/?to=/:account/r2/overview"
    }
}

/** The account has as many D1 databases as the free plan allows. */
class D1Limit : ApiException("Your Cloudflare account has 10 D1 databases, as many as the free plan allows. Delete one, then try again.")

/** The account has no workers.dev subdomain and none was picked, so the worker would have no address. */
class NeedsSubdomain : ApiException("Pick a workers.dev subdomain for your Cloudflare account first.")

/**
 * Deploys [plan]'s release: creates what's missing, uploads the worker, and waits for it to answer. Runs again
 * safely, and an [Plan.Update] keeps the worker's data and its secrets, like REALM_TOKEN. Tells [progress]
 * each step as it starts.
 */
suspend fun CloudflareApi.deploy(plan: Plan.Ready, choices: Choices = Choices(), progress: suspend (Step) -> Unit = {}): Deployed {
    val found = plan.found
    val account = found.account
    val m = plan.release.manifest
    val a = "/accounts/${account.id}/workers"
    // A new worker is off workers.dev until it's turned on; an existing one keeps the addresses it has.
    val wantsWorkersDev = plan is Plan.Fresh || (found.domains.isEmpty() && !found.workersDevOn)
    val newSubdomain = choices.subdomain?.trim()?.lowercase()?.ifEmpty { null }.takeIf { found.subdomain == null }
    val subdomain = found.subdomain ?: newSubdomain
    if (wantsWorkersDev && subdomain == null) throw NeedsSubdomain()

    if (newSubdomain != null) {
        progress(Step.Subdomain(newSubdomain))
        // Unverified: how an account without a workers.dev subdomain gets one.
        call<JsonElement?>(HttpMethod.Put, "$a/subdomain", buildJsonObject { put("subdomain", newSubdomain) })
    }
    for (b in m.buckets) {
        progress(Step.Bucket(b.bucketName))
        ensureBucket(account, b.bucketName)
    }
    val databases = m.databases.associate { d ->
        progress(Step.Database(d.databaseName))
        d.databaseName to ensureDatabase(account, d.databaseName)
    }

    progress(Step.Upload(plan.release.tag))
    upload(account, plan.release, databases)
    progress(Step.Schedule)
    call<JsonElement?>(
        HttpMethod.Put,
        "$a/scripts/${Cloudflare.WORKER}/schedules",
        buildJsonArray { m.crons.forEach { addJsonObject { put("cron", it) } } },
    )
    if (wantsWorkersDev && subdomain != null) {
        progress(Step.WorkersDev(CloudflareApi.workersDev(subdomain)))
        call<JsonElement?>(
            HttpMethod.Post,
            "$a/scripts/${Cloudflare.WORKER}/subdomain",
            buildJsonObject {
                put("enabled", true)
                put("previews_enabled", false)
            },
        )
    }
    val domain = choices.domain?.let {
        progress(Step.Domain(it.hostname))
        attachDomain(account, it)
    }

    val hostname = (domain as? DomainOutcome.Attached)?.domain?.hostname ?: found.domains.firstOrNull()?.hostname
    val origin = hostname?.let { "https://$it" }
        ?: subdomain?.let { CloudflareApi.workersDev(it) }
        ?: throw NeedsSubdomain()
    progress(Step.Waiting(origin))
    return Deployed(account, origin, waitForOwner(origin), domain)
}

/**
 * Asks the worker at [origin] what it is, with this login as its owner, every couple of seconds until it says
 * so or [within] runs out; null then. A new address answers within seconds, though a resolver that asked too
 * early may take minutes.
 */
suspend fun CloudflareApi.waitForOwner(origin: String, within: Duration = 60.seconds): Info? {
    val api = Api.server(origin, bearer, "deploy", engine)
    val start = TimeSource.Monotonic.markNow()
    try {
        // Bounded by tries as well as by the clock, which coroutine tests don't move.
        repeat((within / POLL).toInt().coerceAtLeast(1)) {
            try {
                api.info().takeIf { it.scope == "owner" }?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareRejected) {
                throw e
            } catch (e: Exception) {
                // not there yet: DNS, the certificate, or REALM_ACCOUNT still on its way
            }
            if (start.elapsedNow() >= within) return null
            delay(POLL)
        }
        return null
    } finally {
        api.close()
    }
}

private val POLL = 2.seconds

private suspend fun CloudflareApi.ensureBucket(account: Account, name: String) {
    try {
        call<JsonElement?>(HttpMethod.Post, "/accounts/${account.id}/r2/buckets", buildJsonObject { put("name", name) })
    } catch (e: CloudflareError) {
        when {
            BUCKET_EXISTS in e.codes -> {}
            R2_OFF in e.codes -> throw R2NotEnabled()
            else -> throw e
        }
    }
}

/** The id of the D1 database called [name], created if there's none. Looks first: the plan limit is checked before "exists". */
private suspend fun CloudflareApi.ensureDatabase(account: Account, name: String): String {
    database(account, name)?.let { return it.uuid }
    return try {
        call<D1>(HttpMethod.Post, "/accounts/${account.id}/d1/database", buildJsonObject { put("name", name) }).uuid
    } catch (e: CloudflareError) {
        when {
            D1_LIMIT in e.codes -> throw D1Limit()
            D1_EXISTS in e.codes -> database(account, name)?.uuid ?: throw e
            else -> throw e
        }
    }
}

/**
 * Uploads the worker with its bindings. The module part's file name must be main_module (else 10021);
 * REALM_ACCOUNT goes inline, so Cloudflare logins own it from the start, and keep_bindings keeps the other
 * secrets, like REALM_TOKEN, on an update.
 */
private suspend fun CloudflareApi.upload(account: Account, release: Release, databases: Map<String, String>) {
    val m = release.manifest
    val metadata = buildJsonObject {
        put("main_module", m.mainModule)
        put("compatibility_date", m.compatibilityDate)
        putJsonArray("bindings") {
            for (b in m.buckets) {
                addJsonObject {
                    put("type", "r2_bucket")
                    put("name", b.binding)
                    put("bucket_name", b.bucketName)
                }
            }
            for (d in m.databases) {
                addJsonObject {
                    put("type", "d1")
                    put("name", d.binding)
                    put("id", databases.getValue(d.databaseName))
                }
            }
            addJsonObject {
                put("type", "secret_text")
                put("name", "REALM_ACCOUNT")
                put("text", account.id)
            }
        }
        putJsonArray("keep_bindings") { add("secret_text") }
    }
    val form = MultiPartFormDataContent(
        formData {
            append("metadata", metadata.toString(), Headers.build { append(HttpHeaders.ContentType, "application/json") })
            append(
                m.mainModule,
                release.code,
                Headers.build {
                    append(HttpHeaders.ContentType, "application/javascript+module")
                    append(HttpHeaders.ContentDisposition, "filename=\"${m.mainModule}\"")
                },
            )
        },
    )
    send(HttpMethod.Put, "/accounts/${account.id}/workers/scripts/${Cloudflare.WORKER}") { setBody(form) }
}

// Cloudflare's error codes, as seen in a live run unless they say otherwise.
private const val BUCKET_EXISTS = 10004
private const val D1_EXISTS = 7502
private const val D1_LIMIT = 7406
