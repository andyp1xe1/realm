package realm.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import realm.Prefs
import realm.logFailure
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions

/**
 * The desktop app's settings, kept between runs: the Cloudflare login, the connection, the realms and the share
 * links. One JSON object of strings in [file], read once and written whole on each change, readable only by
 * the user, since it holds a login. java.util.prefs, which kept them before, refuses values over 8 KB.
 *
 * A file that can't be read is kept aside as `app.json.bad`, and the app starts as if nothing were kept.
 */
class SettingsFile(private val file: File) : Prefs {
    private val values: MutableMap<String, String> = if (file.exists()) read() else mutableMapOf()

    @Synchronized override fun get(key: String): String? = values[key]

    @Synchronized override fun set(key: String, value: String?) {
        if (values[key] == value) return
        if (value == null) values.remove(key) else values[key] = value
        write()
    }

    private fun read(): MutableMap<String, String> = try {
        Json.parseToJsonElement(file.readText()).jsonObject.mapValuesTo(mutableMapOf()) { (_, v) ->
            (v as JsonPrimitive).also { check(it.isString) }.content
        }
    } catch (_: Exception) {
        // Not the parser's message: it quotes the file, which holds a login.
        val bad = File(file.path + ".bad")
        val kept = runCatching { Files.move(file.toPath(), bad.toPath(), REPLACE_EXISTING) }.isSuccess
        logFailure("Reading the settings", IOException("$file isn't a JSON object of strings" + if (kept) "; kept it as $bad" else ""))
        mutableMapOf()
    }

    private fun write(values: Map<String, String> = this.values) {
        try {
            val dir = file.absoluteFile.parentFile.toPath()
            val tmp = if (posix) {
                if (!Files.isDirectory(dir)) Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_MODE))
                Files.createTempFile(dir, file.name, ".tmp", PosixFilePermissions.asFileAttribute(FILE_MODE))
            } else {
                Files.createDirectories(dir)
                Files.createTempFile(dir, file.name, ".tmp")
            }
            try {
                Files.writeString(tmp, JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString())
                // Whole or not at all: a crash midway leaves the last whole file.
                try {
                    Files.move(tmp, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp, file.toPath(), REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(tmp)
            }
        } catch (e: IOException) {
            logFailure("Saving the settings to $file", e)
        }
    }

    private companion object {
        val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
        val DIR_MODE = PosixFilePermissions.fromString("rwx------")
        val FILE_MODE = PosixFilePermissions.fromString("rw-------")
    }
}
