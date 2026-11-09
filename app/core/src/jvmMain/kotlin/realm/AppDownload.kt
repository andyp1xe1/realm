package realm

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.SignatureException
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal actual fun verifies(publicKey: String, data: ByteArray, signature: ByteArray): Boolean {
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
    return try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(data)
            verify(signature)
        }
    } catch (_: SignatureException) {
        false
    }
}

/**
 * Downloads the update into [into], telling [progress] how far it has got, from 0 to 1. [into] is left only once the
 * whole file has come and matches its SHA-256; else it's deleted, and a mismatch is [DamagedDownload].
 */
suspend fun AppUpdate.download(into: File, engine: HttpClientEngine? = null, progress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
    into.parentFile?.mkdirs()
    var whole = false
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        client(engine).use { http ->
            http.prepareGet(url) { header("User-Agent", "realm") }.execute { res ->
                if (!res.status.isSuccess()) throw ApiException("GitHub said no (HTTP ${res.status.value})", res.status.value)
                val size = res.contentLength()
                val body = res.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                into.outputStream().use { out ->
                    while (true) {
                        val n = body.readAvailable(buffer)
                        if (n == -1) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (size != null && size > 0) progress(done.toFloat() / size)
                    }
                }
            }
        }
        if (digest.digest().toHexString() != sha256) throw DamagedDownload(tag)
        whole = true
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        throw e
    } catch (e: Exception) {
        throw ApiException("Can't download release $tag", cause = e)
    } finally {
        if (!whole) into.delete()
    }
}
