package realm

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** [prefs]' value at [key], as [serializer] reads and writes it. */
class Remembered<T : Any>(private val prefs: Prefs, private val key: String, private val serializer: KSerializer<T>) {
    /** The last value kept; null when there's none or it no longer reads. */
    fun get(): T? {
        val text = prefs.get(key) ?: return null
        return try {
            json.decodeFromString(serializer, text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Keeps [value] for next time; null forgets it. */
    fun set(value: T?) = prefs.set(key, value?.let { json.encodeToString(serializer, it) })

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
