package realm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
)

@Serializable
data class Page(val items: List<Item>, val next: String? = null)

@Serializable
data class Link(
    val token: String,
    val scope: String,
    val item: String? = null,
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
private data class NewLink(val scope: String, val item: String? = null, val ttl: Long? = null, val label: String? = null)

@Serializable
private data class NewUpload(val name: String, val type: String?, val text: String?)

@Serializable
private data class UploadId(val upload: String)

@Serializable
private data class Part(val part: Int, val etag: String)

@Serializable
private data class Parts(val parts: List<Part>)

class ApiException(message: String) : Exception(message)

/**
 * [url] is either the worker origin (used with [token]) or a share link (…/s/<token>), which carries
 * its own permissions and needs no token.
 */
class Api(url: String, private val token: String?, private val source: String) {
    val base: String = url.trimEnd('/').let { if ("/s/" in it || it.endsWith("/api")) it else "$it/api" }
    val auth: String? = token?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    private val http = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
    }

    private fun HttpRequestBuilder.common() {
        auth?.let { header("Authorization", it) }
        header("X-Realm-Source", source)
    }

    private suspend fun HttpResponse.ok(): HttpResponse {
        if (status.isSuccess()) return this
        val msg = runCatching { body<ErrorBody>().error }.getOrNull() ?: status.description.ifBlank { "HTTP ${status.value}" }
        throw ApiException(msg)
    }

    suspend fun ping() {
        http.get("$base/") { common() }.ok()
    }

    suspend fun list(query: String? = null, kinds: List<String> = emptyList(), before: String? = null, after: String? = null, limit: Int = 60): Page =
        http.get("$base/items") {
            common()
            if (!query.isNullOrBlank()) parameter("q", query)
            if (kinds.isNotEmpty()) parameter("kind", kinds.joinToString(","))
            before?.let { parameter("before", it) }
            after?.let { parameter("after", it) }
            parameter("limit", limit)
        }.ok().body()

    suspend fun note(text: String): Item =
        http.post("$base/items") {
            common()
            contentType(ContentType.Application.Json)
            setBody(TextBody(text))
        }.ok().body()

    suspend fun edit(id: String, text: String?): Item =
        http.patch("$base/items/$id") {
            common()
            contentType(ContentType.Application.Json)
            setBody(TextBody(text))
        }.ok().body()

    suspend fun delete(id: String) {
        http.delete("$base/items/$id") { common() }.ok()
    }

    fun rawUrl(id: String, download: Boolean = false) = "$base/items/$id/raw" + if (download) "?dl=1" else ""

    suspend fun bytes(id: String): ByteArray = http.get(rawUrl(id)) { common() }.ok().body()

    /** Uploads a file; small ones in one request, big ones as an R2 multipart upload. */
    suspend fun upload(file: LocalFile, caption: String?, progress: (Float) -> Unit): Item = withContext(Dispatchers.IO) {
        val type = file.mime ?: "application/octet-stream"
        file.reader().use { r ->
            if (file.size in 0..PART) {
                val bytes = r.readUpTo(PART + 1)
                http.put("$base/files/${file.name.encodeURLPathPart()}") {
                    common()
                    caption?.takeIf { it.isNotBlank() }?.let { parameter("text", it) }
                    contentType(runCatching { ContentType.parse(type) }.getOrElse { ContentType.Application.OctetStream })
                    setBody(bytes)
                    onUpload { sent, total -> progress(sent.toFloat() / (total ?: bytes.size.toLong()).coerceAtLeast(1)) }
                }.ok().body<Item>()
            } else {
                val up = http.post("$base/uploads") {
                    common()
                    contentType(ContentType.Application.Json)
                    setBody(NewUpload(file.name, type, caption?.takeIf { it.isNotBlank() }))
                }.ok().body<UploadId>().upload
                try {
                    val parts = mutableListOf<Part>()
                    var done = 0L
                    while (true) {
                        val chunk = r.readUpTo(PART)
                        if (chunk.isEmpty()) break
                        val n = parts.size + 1
                        parts += http.put("$base/uploads/$up/$n") {
                            common()
                            setBody(chunk)
                            onUpload { sent, _ -> if (file.size > 0) progress((done + sent).toFloat() / file.size) }
                        }.ok().body<Part>()
                        done += chunk.size
                    }
                    http.post("$base/uploads/$up/complete") {
                        common()
                        contentType(ContentType.Application.Json)
                        setBody(Parts(parts))
                    }.ok().body<Item>()
                } catch (e: Throwable) {
                    runCatching { http.delete("$base/uploads/$up") { common() } }
                    throw e
                }
            }
        }
    }

    suspend fun links(): List<Link> = http.get("$base/links") { common() }.ok().body<Links>().links

    suspend fun createLink(scope: String, item: String? = null, ttlSeconds: Long? = null, label: String? = null): Link =
        http.post("$base/links") {
            common()
            contentType(ContentType.Application.Json)
            setBody(NewLink(scope, item, ttlSeconds, label?.takeIf { it.isNotBlank() }))
        }.ok().body()

    suspend fun revoke(token: String) {
        http.delete("$base/links/$token") { common() }.ok()
    }

    /** The cheat-sheet the server hands to agents. */
    suspend fun help(): String = http.get("$base/") { common() }.ok().bodyAsText()

    fun close() = http.close()

    companion object {
        /** Part size for multipart uploads; also the largest single-request upload. R2 wants equal parts. */
        const val PART = 16 * 1024 * 1024
    }
}

internal fun ByteReader.readAll(size: Int): ByteArray = readUpTo(size)

private fun ByteReader.readUpTo(max: Int): ByteArray {
    var buf = ByteArray(minOf(max, 1 shl 20))
    var n = 0
    while (n < max) {
        if (n == buf.size) buf = buf.copyOf(minOf(max, buf.size * 2))
        val r = read(buf, n, buf.size - n)
        if (r < 0) break
        n += r
    }
    return if (n == buf.size) buf else buf.copyOf(n)
}
