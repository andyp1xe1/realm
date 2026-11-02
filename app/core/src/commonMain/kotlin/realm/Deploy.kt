package realm

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

/** How a just-deployed worker answered its owner. */
sealed interface Answer {
    data class Owner(val info: Info) : Answer

    /** It didn't take the login as its owner's in time; [last] is the last error it gave, null when it gave none. */
    data class NotYet(val last: ApiException?) : Answer
}

data class Deployed(
    val account: Account,
    /** Where to connect: its custom domain if it has one, else workers.dev. */
    val origin: String,
    /** What the worker said at [origin] to its owner, or that it didn't in time, as a new domain may take minutes. */
    val answer: Answer,
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

/** The steps deploying [this] with [choices] takes, in order; [deploy] reports each as it starts. */
fun Plan.Ready.steps(choices: Choices = Choices()): List<Step> {
    val m = release.manifest
    val newSubdomain = newSubdomain(choices)
    val subdomain = found.subdomain ?: newSubdomain
    val hostname = choices.domain?.hostname ?: found.domains.firstOrNull()?.hostname
    val origin = hostname?.let { "https://$it" } ?: subdomain?.let { CloudflareApi.workersDev(it) }
    return listOfNotNull(newSubdomain?.let { Step.Subdomain(it) }) +
        m.buckets.map { Step.Bucket(it.bucketName) } +
        m.databases.map { Step.Database(it.databaseName) } +
        listOfNotNull(
            Step.Upload(release.tag),
            Step.Schedule,
            subdomain?.takeIf { wantsWorkersDev }?.let { Step.WorkersDev(CloudflareApi.workersDev(it)) },
            choices.domain?.let { Step.Domain(it.hostname) },
            origin?.let { Step.Waiting(it) },
        )
}

private val Plan.Ready.wantsWorkersDev get() = this is Plan.Fresh || (found.domains.isEmpty() && !found.workersDevOn)

private fun Plan.Ready.newSubdomain(choices: Choices) =
    choices.subdomain?.trim()?.lowercase()?.ifEmpty { null }.takeIf { found.subdomain == null }

/**
 * Deploys [plan]'s release and waits for it to answer; safe to run again, and a [Plan.Update] keeps the worker's
 * data and secrets. [progress] gets each of [steps] as it starts, a [Step.Waiting] at the origin it came to.
 */
suspend fun CloudflareApi.deploy(plan: Plan.Ready, choices: Choices = Choices(), progress: suspend (Step) -> Unit = {}): Deployed {
    val found = plan.found
    val account = found.account
    val a = "/accounts/${account.id}/workers"
    val subdomain = found.subdomain ?: plan.newSubdomain(choices)
    if (plan.wantsWorkersDev && subdomain == null) throw NeedsSubdomain()
    val databases = mutableMapOf<String, String>()
    var domain: DomainOutcome? = null
    for (step in plan.steps(choices)) {
        if (step !is Step.Waiting) progress(step)
        when (step) {
            is Step.Subdomain -> call<JsonElement?>(HttpMethod.Put, "$a/subdomain", buildJsonObject { put("subdomain", step.name) })

            is Step.Bucket -> ensureBucket(account, step.name)

            is Step.Database -> databases[step.name] = ensureDatabase(account, step.name)

            is Step.Upload -> upload(account, plan.release, databases)

            Step.Schedule -> call<JsonElement?>(
                HttpMethod.Put,
                "$a/scripts/${Cloudflare.WORKER}/schedules",
                buildJsonArray { plan.release.manifest.crons.forEach { addJsonObject { put("cron", it) } } },
            )

            is Step.WorkersDev -> call<JsonElement?>(
                HttpMethod.Post,
                "$a/scripts/${Cloudflare.WORKER}/subdomain",
                buildJsonObject {
                    put("enabled", true)
                    put("previews_enabled", false)
                },
            )

            is Step.Domain -> domain = choices.domain?.let { attachDomain(account, it) }

            is Step.Waiting -> {
                val hostname = (domain as? DomainOutcome.Attached)?.domain?.hostname ?: found.domains.firstOrNull()?.hostname
                val origin = hostname?.let { "https://$it" } ?: subdomain?.let { CloudflareApi.workersDev(it) } ?: throw NeedsSubdomain()
                progress(Step.Waiting(origin))
                return Deployed(account, origin, waitForOwner(origin), domain)
            }
        }
    }
    throw NeedsSubdomain()
}

/** Polls the worker at [origin] until it takes this login as its owner, for at most [within]. */
suspend fun CloudflareApi.waitForOwner(origin: String, within: Duration = 60.seconds): Answer {
    val api = Api.server(origin, bearer, "deploy", engine)
    var last: ApiException? = null
    return try {
        withTimeoutOrNull(within) { pollOwner(api) { last = it } }?.let(Answer::Owner) ?: Answer.NotYet(last)
    } finally {
        api.close()
    }
}

private suspend fun pollOwner(api: Api, failed: (ApiException) -> Unit): Info {
    while (true) {
        try {
            api.info().takeIf { it.scope == "owner" }?.let { return it }
        } catch (e: ApiException) {
            failed(e)
        }
        delay(POLL)
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
