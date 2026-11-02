// JVM helpers shared by androidApp and desktopApp (compiled into both as an extra source dir).
package realm.jvm

import realm.ByteReader
import realm.LocalFile
import realm.Prefs
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class JFile(val file: File, override val mime: String? = guessMime(file.name), override val name: String = file.name) : LocalFile {
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

/** Streams [url] into [dest], which appears only once whole. Blocking; call from an IO thread. */
fun download(url: String, auth: String?, dest: File) {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    auth?.let { conn.setRequestProperty("Authorization", it) }
    conn.setRequestProperty("X-Realm-Source", "download")
    try {
        if (conn.responseCode >= 400) {
            val body = conn.errorStream?.bufferedReader()?.readText().orEmpty()
            val msg = Regex("\"error\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
            throw IOException(msg ?: "HTTP ${conn.responseCode}")
        }
        writeAtomically(dest) { tmp -> conn.inputStream.use { Files.copy(it, tmp, REPLACE_EXISTING) } }
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

/** Image bytes cached in [dir], least recently used dropped past [maxBytes]. Blocking. */
class DiskCache(private val dir: File, private val maxBytes: Long) {
    fun get(key: String): ByteArray? {
        val f = file(key)
        if (!f.isFile) return null
        f.setLastModified(System.currentTimeMillis())
        return runCatching { f.readBytes() }.getOrNull()
    }

    fun put(key: String, bytes: ByteArray) {
        runCatching {
            writeAtomically(file(key), bytes)
            trim()
        }
    }

    private fun file(key: String) = File(dir, safeFileName(key))

    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(TMP) } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }
}

/** Strings kept as files in [dir], one per key. Blocking. */
class FileStore(private val dir: File) : Prefs {
    override fun get(key: String): String? = runCatching { file(key).takeIf { it.isFile }?.readText() }.getOrNull()

    override fun set(key: String, value: String?) {
        runCatching {
            val f = file(key)
            if (value == null) {
                f.delete()
                return
            }
            writeAtomically(f, value.toByteArray())
        }
    }

    private fun file(key: String) = File(dir, safeFileName(key))
}

/** A file name for [key]: its letters and digits, cut short, then a hash of the whole key, so distinct keys never share a file. */
fun safeFileName(key: String): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    return key.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").take(60) + "-" + hash
}

/** Writes [bytes] to [file] so that readers see either the old content or all of the new. Blocking. */
fun writeAtomically(file: File, bytes: ByteArray, ownerOnly: Boolean = false) = writeAtomically(file, ownerOnly) { Files.write(it, bytes) }

/**
 * Lets [write] fill a temporary file next to [file], then moves it into place.
 * With [ownerOnly], the file and any folder this creates for it are readable only by the user, where the file system has POSIX permissions.
 */
fun writeAtomically(file: File, ownerOnly: Boolean = false, write: (Path) -> Unit) {
    val dir = file.toPath().toAbsolutePath().parent
    val private = ownerOnly && "posix" in FileSystems.getDefault().supportedFileAttributeViews()
    val tmp = if (private) {
        if (!Files.isDirectory(dir)) Files.createDirectories(dir, ownerOnlyMode("rwx------"))
        Files.createTempFile(dir, file.name, TMP, ownerOnlyMode("rw-------"))
    } else {
        Files.createDirectories(dir)
        Files.createTempFile(dir, file.name, TMP)
    }
    try {
        write(tmp)
        try {
            Files.move(tmp, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file.toPath(), REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(tmp)
    }
}

private fun ownerOnlyMode(mode: String) = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode))

private const val TMP = ".tmp"
