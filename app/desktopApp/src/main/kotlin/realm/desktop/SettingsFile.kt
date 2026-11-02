package realm.desktop

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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

/** The desktop app's settings: one JSON object of strings in [file], readable only by the user. */
class SettingsFile(private val file: File) : Prefs {
    private val values: MutableMap<String, String> = if (file.exists()) read() else mutableMapOf()

    @Synchronized override fun get(key: String): String? = values[key]

    @Synchronized override fun set(key: String, value: String?) {
        if (values[key] == value) return
        if (value == null) values.remove(key) else values[key] = value
        write()
    }

    private fun read(): MutableMap<String, String> = try {
        val all = Json.parseToJsonElement(file.readText()) as? JsonObject ?: error("not an object")
        all.mapValuesTo(mutableMapOf()) { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("not a string") }
    } catch (_: SerializationException) {
        setAside("$file isn't JSON")
    } catch (_: IllegalStateException) {
        setAside("$file isn't a JSON object of strings")
    } catch (e: IOException) {
        setAside("$file can't be read: $e")
    }

    private fun setAside(why: String): MutableMap<String, String> {
        val bad = File(file.path + ".bad")
        val kept = runCatching { Files.move(file.toPath(), bad.toPath(), REPLACE_EXISTING) }.isSuccess
        logFailure("Reading the settings", why + if (kept) "; kept it as $bad" else "")
        return mutableMapOf()
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
