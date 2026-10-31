// Moving bytes: uploading files, streaming downloads, and the file behind a link to just it.
package realm

import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.delete
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
private data class NewUpload(val name: String, val type: String?, val text: String?)

@Serializable
private data class UploadId(val upload: String)

@Serializable
private data class Part(val part: Int, val etag: String)

@Serializable
private data class Parts(val parts: List<Part>)

/**
 * Uploads a file; small ones in one request, big ones as an R2 multipart upload. One of unknown size goes
 * up in parts until it ends.
 */
suspend fun Api.upload(file: LocalFile, caption: String?, progress: (Float) -> Unit): Item = withContext(Dispatchers.IO) {
    val type = file.mime ?: "application/octet-stream"
    file.reader().use { r ->
        if (file.size in 0..Api.PART) {
            val bytes = r.readUpTo(Api.PART + 1)
            http.put("$base/files/${file.name.encodeURLPathPart()}") {
                caption?.takeIf { it.isNotBlank() }?.let { parameter("text", it) }
                contentType(runCatching { ContentType.parse(type) }.getOrElse { ContentType.Application.OctetStream })
                setBody(bytes)
                onUpload { sent, total -> progress(sent.toFloat() / (total ?: bytes.size.toLong()).coerceAtLeast(1)) }
            }.ok().body<Item>()
        } else {
            val up = http.post("$base/uploads") {
                contentType(ContentType.Application.Json)
                setBody(NewUpload(file.name, type, caption?.takeIf { it.isNotBlank() }))
            }.ok().body<UploadId>().upload
            try {
                val parts = mutableListOf<Part>()
                var done = 0L
                while (true) {
                    val chunk = r.readUpTo(Api.PART)
                    if (chunk.isEmpty()) break
                    val n = parts.size + 1
                    parts += http.put("$base/uploads/$up/$n") {
                        setBody(chunk)
                        onUpload { sent, _ -> if (file.size > 0) progress((done + sent).toFloat() / file.size) }
                    }.ok().body<Part>()
                    done += chunk.size
                }
                http.post("$base/uploads/$up/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(Parts(parts))
                }.ok().body<Item>()
            } catch (e: Throwable) {
                runCatching { http.delete("$base/uploads/$up") }
                throw e
            }
        }
    }
}

/**
 * Streams an item's bytes into [to] a piece at a time, so a big file never sits in memory. [progress] gets the
 * bytes so far and the total, when the server says it. Returns how many bytes came. On a use-limited link,
 * this spends a use.
 */
suspend fun Api.download(id: String, to: ByteWriter, progress: (done: Long, total: Long?) -> Unit = { _, _ -> }): Long =
    withContext(Dispatchers.IO) {
        http.prepareGet(rawUrl(id)).execute { res ->
            res.ok()
            val total = res.contentLength()
            val body = res.bodyAsChannel()
            val buf = ByteArray(1 shl 16)
            var done = 0L
            while (true) {
                val n = body.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                to.write(buf, 0, n)
                done += n
                progress(done, total)
            }
            done
        }
    }

/** A file someone shared with a link to just it. Close [api] once it's downloaded. */
class SharedFile(val api: Api, val item: Item)

/**
 * The one file behind a share link to it, found without connecting to the link: `realm get <link>`. Asking
 * spends none of the link's uses; downloading it spends one.
 */
suspend fun sharedFile(link: String, source: String, engine: HttpClientEngine? = null): SharedFile {
    val url = linkUrl(link) ?: throw ApiException("That's not a share link")
    val api = Api(url, null, source, engine)
    val item = api.closingOnFailure {
        api.info().item ?: throw ApiException("That link shares more than one file. Connect to it to look around.")
    }
    return SharedFile(api, item)
}

/**
 * The name to save an item under: its file name, without any folders a server could slip into it, or its
 * id as a .txt for a note.
 */
val Item.fileName: String
    get() = name?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() && it != "." && it != ".." }
        ?: "$id.txt"

fun ByteReader.readAll(size: Int): ByteArray = readUpTo(size)

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
