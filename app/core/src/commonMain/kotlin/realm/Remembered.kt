package realm

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
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

/** An answer kept in [prefs] at [key] with when it came, for asking at most once a day; [now] is in Unix seconds. */
class Daily<T>(prefs: Prefs, key: String, serializer: KSerializer<T>, private val now: () -> Long = Cloudflare::now) {
    private val kept = Remembered(prefs, key, Dated.serializer(serializer))

    /** The last answer kept, however old; null when there's none. */
    fun get(): T? = kept.get()?.value

    /** Whether an answer came in the last day. */
    val fresh: Boolean get() = kept.get()?.let { now() - it.at < DAY } == true

    fun set(value: T) = kept.set(Dated(now(), value))

    private companion object {
        const val DAY = 24 * 60 * 60L
    }
}

@Serializable
private class Dated<T>(val at: Long, val value: T)
