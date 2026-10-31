// The Cloudflare REST API as one login sees it: its accounts, the realm worker on them and where it answers.
// Deploying (Plan.kt, Deploy.kt, Domains.kt) builds on the same client.
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

/**
 * Cloudflare said no. [codes] are its error codes, like 10004 for an R2 bucket that already exists, for
 * callers that tell one refusal from another; the message is Cloudflare's own words.
 */
class CloudflareError(message: String, status: Int, val codes: List<Int>) : ApiException(message, status)

/** A Cloudflare account the login can use. */
@Serializable
data class Account(val id: String, val name: String = "")

/** A domain on a Cloudflare account, to put the worker on. */
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

/** A DNS record, as one standing in the way of a custom domain. */
@Serializable
data class DnsRecord(val id: String, val type: String, val name: String, val content: String = "")

/**
 * The Cloudflare API with [bearer]'s login. [engine] is the platform's own HTTP engine unless one is given, as
 * tests do; it also carries the GitHub and worker requests that deploying makes.
 */
class CloudflareApi(internal val bearer: Bearer, internal val engine: HttpClientEngine? = null) : AutoCloseable {
    internal val http = client(engine)

    /** The accounts the login was given on the consent screen. */
    suspend fun accounts(): List<Account> = call(HttpMethod.Get, "/accounts")

    /** The accounts that have a realm worker, and where each answers. */
    suspend fun workers(): List<Worker> {
        val accounts = accounts()
        if (accounts.isEmpty()) throw ApiException("No Cloudflare account was picked on the consent screen")
        return accounts.mapNotNull { acc -> if (hasScript(acc)) Worker(acc.id, acc.name, origin(acc)) else null }
    }

    internal suspend fun hasScript(account: Account): Boolean =
        call<List<Script>>(HttpMethod.Get, "/accounts/${account.id}/workers/scripts").any { it.id == Cloudflare.WORKER }

    /** The custom domains the realm worker answers on. */
    suspend fun domains(account: Account): List<Domain> =
        call<List<Domain>>(HttpMethod.Get, "/accounts/${account.id}/workers/domains?service=${Cloudflare.WORKER}")
            .filter { it.service == Cloudflare.WORKER }
            .sortedBy { it.hostname }

    /** The account's workers.dev subdomain, like "jo" in realm.jo.workers.dev; null when it has none yet. */
    suspend fun subdomain(account: Account): String? = try {
        call<Subdomain>(HttpMethod.Get, "/accounts/${account.id}/workers/subdomain").subdomain
    } catch (e: CloudflareError) {
        // Unverified: an account without one is believed to answer 10007 (or a 404).
        if (NO_SUBDOMAIN in e.codes || e.status == 404) null else throw e
    }

    /** Whether the realm worker answers on its workers.dev address. */
    internal suspend fun workersDevOn(account: Account): Boolean =
        call<ScriptSubdomain>(HttpMethod.Get, "/accounts/${account.id}/workers/scripts/${Cloudflare.WORKER}/subdomain").enabled

    /**
     * Where the realm worker on [account] answers: a custom domain if it has one, else its workers.dev
     * address. Throws when it has neither, as nobody can reach it then.
     */
    suspend fun origin(account: Account, domains: List<Domain>? = null): String {
        (domains ?: domains(account)).firstOrNull()?.let { return "https://${it.hostname}" }
        val sub = if (workersDevOn(account)) subdomain(account) else null
        return sub?.let { workersDev(it) } ?: throw ApiException(
            "The realm worker on ${account.name.ifBlank { account.id }} has no address: its workers.dev address is off " +
                "and it has no custom domain. Turn one on in the Cloudflare dashboard.",
        )
    }

    /** Calls the Cloudflare API with [body] as JSON, if any, and decodes its "result". */
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

    /** Sends one request; the envelope's result, or a [CloudflareError] with Cloudflare's codes. */
    internal suspend fun send(method: HttpMethod, path: String, configure: HttpRequestBuilder.() -> Unit): JsonElement? {
        val res: HttpResponse = try {
            http.request("$API$path") {
                this.method = method
                header("Authorization", "Bearer ${bearer.token()}")
                configure() // not build(): that would be the builder's own
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

    /** Tells the realm worker on [account] which account it runs on, if it doesn't know yet: then Cloudflare logins are its owner. */
    suspend fun claim(account: String) {
        val script = "/accounts/$account/workers/scripts/${Cloudflare.WORKER}/secrets"
        if (call<List<Secret>>(HttpMethod.Get, script).none { it.name == "REALM_ACCOUNT" }) {
            call<JsonElement?>(HttpMethod.Put, script, cloudflareJson.encodeToJsonElement(Secret("REALM_ACCOUNT", account)))
        }
    }

    @Serializable
    private data class Secret(val name: String, val text: String? = null, val type: String = "secret_text")

    override fun close() = http.close()

    /** Cloudflare's reply to [request] didn't parse: logged with [cause], what didn't, so a mismatch with its real replies shows. */
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
    // errors can be null: the custom domains endpoint answers "errors":null where the others send [].
    private data class Envelope(val success: Boolean = false, val errors: List<Problem>? = null, val result: JsonElement? = null)

    @Serializable
    private data class Problem(val code: Int = 0, val message: String = "")

    internal companion object {
        const val API = "https://api.cloudflare.com/client/v4"

        /** Unverified: what an account without a workers.dev subdomain is believed to answer. */
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
