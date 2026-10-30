// JVM helpers shared by androidApp and desktopApp (compiled into both as an extra source dir).
package realm.jvm

import realm.ByteReader
import realm.LocalFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class JFile(
    val file: File,
    override val mime: String? = guessMime(file.name),
    override val name: String = file.name,
) : LocalFile {
    override val size: Long get() = file.length()
    override fun reader(): ByteReader = StreamReader(file.inputStream())
}

class StreamReader(private val input: InputStream) : ByteReader {
    override fun read(buf: ByteArray, off: Int, len: Int) = input.read(buf, off, len)
    override fun close() = input.close()
}

fun guessMime(name: String): String? = URLConnection.guessContentTypeFromName(name) ?: when (name.substringAfterLast('.').lowercase()) {
    "m4a" -> "audio/mp4"
    "opus", "ogg" -> "audio/ogg"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "webp" -> "image/webp"
    "heic" -> "image/heic"
    "md" -> "text/markdown"
    else -> null
}

/** Streams [url] into [dest] (via a .part file). Blocking; call from an IO thread. */
fun download(url: String, auth: String?, dest: File) {
    val conn = URL(url).openConnection() as HttpURLConnection
    auth?.let { conn.setRequestProperty("Authorization", it) }
    conn.setRequestProperty("X-Realm-Source", "download")
    try {
        if (conn.responseCode >= 400) {
            val body = conn.errorStream?.bufferedReader()?.readText().orEmpty()
            val msg = Regex("\"error\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
            throw IOException(msg ?: "HTTP ${conn.responseCode}")
        }
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it, 1 shl 16) } }
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
    } finally {
        conn.disconnect()
    }
}

/** "photo.jpg" → "photo (1).jpg" … until it doesn't clash. */
fun uniqueFile(dir: File, name: String): File {
    var f = File(dir, name)
    val base = name.substringBeforeLast('.')
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var i = 1
    while (f.exists()) f = File(dir, "$base (${i++})$ext")
    return f
}

object Dates {
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dayThisYear = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val dayOtherYear = DateTimeFormatter.ofPattern("MMM d, yyyy")

    fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(time)

    fun day(ms: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d.year == today.year -> d.format(dayThisYear)
            else -> d.format(dayOtherYear)
        }
    }
}
