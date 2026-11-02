package realm

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Cloudflare said no, in its own words; [codes] are its error codes, like 10004 for an R2 bucket that already exists. */
class CloudflareError(message: String, status: Int, val codes: List<Int>) : ApiException(message, status)

@Serializable
data class Account(val id: String, val name: String = "")

/** Whether realm runs on an account. */
sealed interface RealmOn {
    data class Running(val worker: Worker) : RealmOn

    /** There's no realm worker on it yet: deploy one. */
    data object None : RealmOn

    /** There's a realm worker, but nothing can reach it: [why] says what to turn on. */
    data class NoAddress(val why: String) : RealmOn
}

@Serializable
data class Zone(val id: String, val name: String, val status: String = "active")

/** A custom domain the worker answers on. */
@Serializable
data class Domain(
    val id: String,
    val hostname: String,
    @SerialName("zone_id") val zoneId: String,
    @SerialName("zone_name") val zoneName: String = "",
    val service: String = Cloudflare.WORKER,
)

@Serializable
data class DnsRecord(val id: String, val type: String, val name: String, val content: String = "")

/** The Cloudflare API with [bearer]'s login; [engine] also carries the GitHub and worker requests that deploying makes. */
class CloudflareApi(internal val bearer: Bearer, internal val engine: HttpClientEngine? = null) : AutoCloseable {
    internal val http = client(engine)

    /** The accounts the login was given on the consent screen. */
    suspend fun accounts(): List<Account> = call(HttpMethod.Get, "/accounts")

    /** Whether realm runs on [account], and where it answers: a custom domain if it has one, else workers.dev. */
    suspend fun realmOn(account: Account): RealmOn {
        if (!hasScript(account)) return RealmOn.None
        return try {
            RealmOn.Running(Worker(account.id, account.name, origin(account)))
        } catch (e: CloudflareError) {
            throw e
        } catch (e: ApiException) {
            RealmOn.NoAddress(e.message.orEmpty())
        }
    }

    internal suspend fun hasScript(account: Account): Boolean =
        call<List<Script>>(HttpMethod.Get, "/accounts/${account.id}/workers/scripts").any { it.id == Cloudflare.WORKER }

    suspend fun domains(account: Account): List<Domain> =
        call<List<Domain>>(HttpMethod.Get, "/accounts/${account.id}/workers/domains?service=${Cloudflare.WORKER}")
            .filter { it.service == Cloudflare.WORKER }
            .sortedBy { it.hostname }

    /** The account's workers.dev subdomain, like "jo" in realm.jo.workers.dev; null when it has none yet. */
    suspend fun subdomain(account: Account): String? = try {
        call<Subdomain>(HttpMethod.Get, "/accounts/${account.id}/workers/subdomain").subdomain
    } catch (e: CloudflareError) {
        if (NO_SUBDOMAIN in e.codes || e.status == 404) null else throw e
    }

    internal suspend fun workersDevOn(account: Account): Boolean =
        call<ScriptSubdomain>(HttpMethod.Get, "/accounts/${account.id}/workers/scripts/${Cloudflare.WORKER}/subdomain").enabled

    /** A custom domain of the realm worker on [account], else its workers.dev address; throws when it has neither. */
    suspend fun origin(account: Account, domains: List<Domain>? = null): String {
        (domains ?: domains(account)).firstOrNull()?.let { return "https://${it.hostname}" }
        val sub = if (workersDevOn(account)) subdomain(account) else null
        return sub?.let { workersDev(it) } ?: throw ApiException(
            "The realm worker on ${account.name.ifBlank { account.id }} has no address: its workers.dev address is off " +
                "and it has no custom domain. Turn one on in the Cloudflare dashboard.",
        )
    }

    internal suspend inline fun <reified T> call(method: HttpMethod, path: String, body: JsonElement? = null): T {
        val text = body?.toString()
        val result = send(method, path) {
            if (text != null) {
                contentType(ContentType.Application.Json)
                setBody(text)
            }
        }
        return try {
            cloudflareJson.decodeFromJsonElement(result ?: JsonNull)
        } catch (e: SerializationException) {
            throw odd("${method.value} $path", e)
        } catch (e: IllegalArgumentException) {
            throw odd("${method.value} $path", e)
        }
    }

    internal suspend fun send(method: HttpMethod, path: String, configure: HttpRequestBuilder.() -> Unit): JsonElement? {
        val res: HttpResponse = try {
            http.request("$API$path") {
                this.method = method
                header("Authorization", "Bearer ${bearer.token()}")
                configure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudflareRejected) {
            throw e
        } catch (e: Exception) {
            throw ApiException("Can't reach Cloudflare", cause = e)
        }
        val env = runCatching {
            res.body<Envelope>()
        }.getOrElse { throw odd("${method.value} $path", it, res.status.value) }
        if (!env.success) {
            throw CloudflareError(
                env.errors.orEmpty().joinToString("; ") { it.message }.ifBlank { "Cloudflare said no (HTTP ${res.status.value})" },
                res.status.value,
                env.errors.orEmpty().map { it.code },
            )
        }
        return env.result
    }

    /** Tells the realm worker which account it runs on, if it doesn't know yet, so Cloudflare logins own it. */
    suspend fun claim(account: String) {
        val script = "/accounts/$account/workers/scripts/${Cloudflare.WORKER}/secrets"
        if (call<List<Secret>>(HttpMethod.Get, script).none { it.name == "REALM_ACCOUNT" }) {
            call<JsonElement?>(HttpMethod.Put, script, cloudflareJson.encodeToJsonElement(Secret("REALM_ACCOUNT", account)))
        }
    }

    @Serializable
    private data class Secret(val name: String, val text: String? = null, val type: String = "secret_text")

    override fun close() = http.close()

    internal fun odd(request: String, cause: Throwable, status: Int? = null): ApiException {
        logFailure("Reading Cloudflare's reply to $request", cause)
        return ApiException("Cloudflare sent something odd" + (status?.let { " (HTTP $it)" } ?: ""), status, cause)
    }

    @Serializable
    internal data class Script(val id: String)

    @Serializable
    internal data class Subdomain(val subdomain: String)

    @Serializable
    internal data class ScriptSubdomain(val enabled: Boolean = false)

    @Serializable
    private data class Envelope(val success: Boolean = false, val errors: List<Problem>? = null, val result: JsonElement? = null)

    @Serializable
    private data class Problem(val code: Int = 0, val message: String = "")

    internal companion object {
        const val API = "https://api.cloudflare.com/client/v4"

        const val NO_SUBDOMAIN = 10007

        fun workersDev(subdomain: String) = "https://${Cloudflare.WORKER}.$subdomain.workers.dev"
    }
}

internal val cloudflareJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal fun client(engine: HttpClientEngine?): HttpClient {
    val setup: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        install(ContentNegotiation) { json(cloudflareJson) }
    }
    return if (engine == null) HttpClient(setup) else HttpClient(engine, setup)
}
