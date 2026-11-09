package realm

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

@Serializable
data class Item(
    val id: String,
    val kind: String,
    val text: String? = null,
    val name: String? = null,
    val mime: String? = null,
    val size: Long? = null,
    val src: String? = null,
    val created: Long,
    val realm: String? = null,
)

/** One page of a feed; [since] is where the server's count of added items stood, for asking what came after. */
@Serializable
data class Page(val items: List<Item>, val next: String? = null, val since: Long? = null)

@Serializable
data class Realm(val id: String, val name: String, val created: Long = 0, val count: Long = 0, val updated: Long? = null)

@Serializable
private data class Realms(val realms: List<Realm>)

@Serializable
private data class RealmName(val name: String)

/** What a server or link says about itself before anything is fetched. Asking never spends a link's use. */
@Serializable
data class Info(
    /** The GitHub "owner/repo" of the fork the server runs, or null for realm itself. */
    val fork: String? = null,
    val version: Int = 0,
    val scope: String,
    val realm: Realm? = null,
    val label: String? = null,
    val expires: Long? = null,
    @SerialName("uses_left") val usesLeft: Long? = null,
    /** Bytes a link with an upload budget can still add. */
    @SerialName("bytes_left") val bytesLeft: Long? = null,
    /** How many items a link that shares picked items shares. */
    val items: Int? = null,
    /** The item, when exactly one is picked. */
    val item: Item? = null,
)

@Serializable
data class Link(
    val token: String,
    val scope: String,
    /** Set for links that share picked items. */
    val items: List<String>? = null,
    val label: String? = null,
    val expires: Long? = null,
    @SerialName("max_uses") val maxUses: Long? = null,
    val uses: Long = 0,
    val url: String,
)

@Serializable
private data class Links(val links: List<Link>)

@Serializable
private data class ErrorBody(val error: String? = null)

@Serializable
private data class TextBody(val text: String?)

@Serializable
private data class NewLink(
    val scope: String,
    val items: List<String>? = null,
    val ttl: Long? = null,
    val label: String? = null,
    val uses: Long? = null,
    @SerialName("max_bytes") val maxBytes: Long? = null,
)

/** What went wrong, in words for the user; [status] is the HTTP status when a server sent one. */
open class ApiException(message: String, val status: Int? = null, cause: Throwable? = null) : Exception(message, cause)

/** The server couldn't be reached: no address yet, no connection, no certificate, or no answer in time. */
class Unreachable(cause: Throwable) : ApiException("Can't reach the server", cause = cause)

/** Whether asking again later may get another answer: the server couldn't be reached, or failed on its side. */
val ApiException.mayPassLater: Boolean get() = this is Unreachable || (status ?: 0) >= 500

/** Where an owner's token comes from: a fixed owner token, or a Cloudflare login that renews itself. */
fun interface Bearer {
    suspend fun token(): String
}

/** [base] is the owner API (…/api, or one realm of it), used with [bearer], or a share link (…/s/<token>), which needs no token. */
class Api(base: String, private val bearer: Bearer?, private val source: String, private val engine: HttpClientEngine? = null) {
    @Volatile var base: String = base.trimEnd('/')
        private set

    /** Moves this API to the same path at [origin], as when its server answers at another address. */
    fun rebase(origin: String) {
        val rest = base.substringAfter("://")
        base = origin.trimEnd('/') + rest.indexOf('/').let { if (it < 0) "" else rest.substring(it) }
    }
    val isOwner get() = bearer != null

    /** The Authorization header for this API; asking may renew a login first. */
    suspend fun auth(): String? = bearer?.let { "Bearer ${it.token()}" }

    private val setup: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        HttpResponseValidator {
            handleResponseExceptionWithRequest { cause, _ -> if (cause is IOException) throw Unreachable(cause) }
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            )
        }
        install(
            createClientPlugin("realm") {
                onRequest { req, _ ->
                    auth()?.let { req.header("Authorization", it) }
                    req.header("X-Realm-Source", source)
                }
            },
        )
    }

    internal val http = if (engine == null) HttpClient(setup) else HttpClient(engine, setup)

    suspend fun info(): Info = http.get("$base/info").ok().body()

    /** The same server, scoped to one realm. */
    fun realm(id: String) = Api("$base/realms/${id.encodeURLPathPart()}", bearer, source, engine)

    suspend fun realms(): List<Realm> = http.get("$base/realms").ok().body<Realms>().realms

    suspend fun createRealm(name: String): Realm = http.post("$base/realms") {
        contentType(ContentType.Application.Json)
        setBody(RealmName(name))
    }.ok().body()

    suspend fun renameRealm(id: String, name: String): Realm = http.patch("$base/realms/${id.encodeURLPathPart()}") {
        contentType(ContentType.Application.Json)
        setBody(RealmName(name))
    }.ok().body()

    suspend fun deleteRealm(id: String) {
        http.delete("$base/realms/${id.encodeURLPathPart()}").ok()
    }

    suspend fun list(
        query: String? = null,
        kinds: List<String> = emptyList(),
        before: String? = null,
        since: Long? = null,
        limit: Int = 60,
    ): Page = http.get("$base/items") {
        if (!query.isNullOrBlank()) parameter("q", query)
        if (kinds.isNotEmpty()) parameter("kind", kinds.joinToString(","))
        before?.let { parameter("before", it) }
        since?.let { parameter("since", it) }
        parameter("limit", limit)
    }.ok().body()

    suspend fun item(id: String): Item = http.get("$base/items/${id.encodeURLPathPart()}").ok().body()

    suspend fun note(text: String): Item = http.post("$base/items") {
        contentType(ContentType.Application.Json)
        setBody(TextBody(text))
    }.ok().body()

    suspend fun edit(id: String, text: String?): Item = http.patch("$base/items/$id") {
        contentType(ContentType.Application.Json)
        setBody(TextBody(text))
    }.ok().body()

    suspend fun delete(id: String) {
        http.delete("$base/items/$id").ok()
    }

    /** Names an item's bytes in the image cache: its server and id, which never change. */
    fun cacheKey(id: String) = base.substringAfter("://").substringBefore('/') + "_" + id

    fun rawUrl(id: String, download: Boolean = false) = "$base/items/$id/raw" + if (download) "?dl=1" else ""

    suspend fun bytes(id: String): ByteArray = http.get(rawUrl(id)).ok().body()

    suspend fun links(): List<Link> = http.get("$base/links").ok().body<Links>().links

    /**
     * With [items], the link shares only those (and can only read). [maxUses] caps how often it works, and
     * [maxBytes] how much a drop or full link can upload; null is no limit, as is a null [ttlSeconds].
     */
    suspend fun createLink(
        scope: String,
        items: List<String>? = null,
        ttlSeconds: Long? = null,
        label: String? = null,
        maxUses: Long? = null,
        maxBytes: Long? = null,
    ): Link = http.post("$base/links") {
        contentType(ContentType.Application.Json)
        setBody(NewLink(scope, items, ttlSeconds, label?.takeIf { it.isNotBlank() }, maxUses, maxBytes))
    }.ok().body()

    suspend fun revoke(token: String) {
        http.delete("$base/links/${token.encodeURLPathPart()}").ok()
    }

    /** The cheat-sheet the server hands to agents. */
    suspend fun help(): String = http.get("$base/").ok().bodyAsText()

    fun close() = http.close()

    companion object {
        /** The owner API of the server at [origin]. */
        fun server(origin: String, bearer: Bearer, source: String, engine: HttpClientEngine? = null) =
            Api(origin.trimEnd('/').removeSuffix("/api") + "/api", bearer, source, engine)

        /** Part size for multipart uploads; also the largest single-request upload. R2 wants equal parts. */
        const val PART = 16 * 1024 * 1024

        /** The fork ("owner/repo", null for realm itself) and API version this app is written for, as in [Info]. */
        val FORK: String? = null
        const val VERSION = 4

        /** The public key, X.509 in base64, that signs the app files in this fork's releases. */
        const val APP_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsMJt/QsC0POS+zJoDX3huZFFi0UyZtHnW9DKyhoMG2KuECbciH8lYp+wHSgEDQyFi4tOMQW0G29Kl+tBM5HCYQ=="

        /** A warning for a server running other code than this app is written for, or null. */
        fun mismatch(info: Info): String? = when {
            info.fork != FORK -> "That server runs ${info.fork ?: "realm"}, version ${info.version}. Some things may not work."
            info.version < VERSION -> "That server needs updating: redeploy the worker."
            info.version > VERSION -> "That server is newer than this app. Update the app."
            else -> null
        }
    }
}

internal suspend fun HttpResponse.ok(): HttpResponse {
    if (status.isSuccess()) return this
    val msg = runCatching { body<ErrorBody>().error }.getOrNull() ?: status.description.ifBlank { "HTTP ${status.value}" }
    throw ApiException(msg, status.value)
}
