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
    data class Subdomain(val name: String) : Step

    data class Bucket(val name: String) : Step

    data class Database(val name: String) : Step

    data class Upload(val tag: Semver) : Step

    data object Schedule : Step

    data class WorkersDev(val origin: String) : Step

    data class Domain(val hostname: String) : Step

    /** Waiting for the worker to answer at [origin] and take this login as its owner. */
    data class Waiting(val origin: String) : Step
}

data class Deployed(
    val account: Account,
    /** Where to connect: its custom domain if it has one, else workers.dev. */
    val origin: String,
    /** What the worker said at [origin] to its owner; null when it didn't answer in time, as a new domain may take minutes. */
    val info: Info?,
    /** What came of [Choices.domain], if one was picked. */
    val domain: DomainOutcome?,
)

class R2NotEnabled : ApiException("Turn on R2 in the Cloudflare dashboard, then try again: $URL") {
    companion object {
        const val URL = "https://dash.cloudflare.com/?to=/:account/r2/overview"
    }
}

class D1Limit :
    ApiException(
        "Your Cloudflare account has as many D1 databases as its plan allows, $D1_FREE_LIMIT on the free plan. Delete one, then try again.",
    )

class NeedsSubdomain : ApiException("Pick a workers.dev subdomain for your Cloudflare account first.")

/** Deploys [plan]'s release and waits for it to answer; safe to run again, and a [Plan.Update] keeps the worker's data and secrets. */
suspend fun CloudflareApi.deploy(plan: Plan.Ready, choices: Choices = Choices(), progress: suspend (Step) -> Unit = {}): Deployed {
    val found = plan.found
    val account = found.account
    val m = plan.release.manifest
    val a = "/accounts/${account.id}/workers"
    val wantsWorkersDev = plan is Plan.Fresh || (found.domains.isEmpty() && !found.workersDevOn)
    val newSubdomain = choices.subdomain?.trim()?.lowercase()?.ifEmpty { null }.takeIf { found.subdomain == null }
    val subdomain = found.subdomain ?: newSubdomain
    if (wantsWorkersDev && subdomain == null) throw NeedsSubdomain()

    if (newSubdomain != null) {
        progress(Step.Subdomain(newSubdomain))
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

/** Polls the worker at [origin] until it takes this login as its owner; null when [within] runs out first. */
suspend fun CloudflareApi.waitForOwner(origin: String, within: Duration = 60.seconds): Info? {
    val api = Api.server(origin, bearer, "deploy", engine)
    val start = TimeSource.Monotonic.markNow()
    try {
        repeat((within / POLL).toInt().coerceAtLeast(1)) {
            try {
                api.info().takeIf { it.scope == "owner" }?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareRejected) {
                throw e
            } catch (e: Exception) {
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

private const val BUCKET_EXISTS = 10004
private const val D1_EXISTS = 7502
private const val D1_LIMIT = 7406
