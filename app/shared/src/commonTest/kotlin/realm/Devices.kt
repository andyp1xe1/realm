// Stand-ins for the device, for tests of what the app holds and shows.
package realm

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.emptyFlow

/** Prefs, or the cache, kept in a map. */
internal class MapPrefs(vararg entries: Pair<String, String>) : Prefs {
    val map = mutableMapOf(*entries)
    override fun get(key: String) = map[key]
    override fun set(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/** A device with nothing to pick, record or show: [prefs] and [cache] are all it keeps. */
internal class TestPlatform(override val prefs: Prefs, override val cache: Prefs = MapPrefs()) : Platform {
    override val name = "test"
    override val incoming = emptyFlow<Incoming>()
    override val isForeground = false
    override val canTakePhoto = false
    override suspend fun pickFiles() = emptyList<LocalFile>()
    override suspend fun takePhoto(): LocalFile? = null
    override suspend fun startRecording(): Recording? = null
    override fun clipboardFiles() = emptyList<LocalFile>()
    override fun copyText(text: String) {}
    override suspend fun save(url: String, auth: String?, name: String) = ""
    override suspend fun open(url: String, auth: String?, name: String) {}
    override fun decodeImage(bytes: ByteArray, maxSize: Int): ImageBitmap? = null
    override fun cachedImage(key: String): ByteArray? = null
    override fun cacheImage(key: String, bytes: ByteArray) {}
    override fun formatTime(epochMs: Long) = ""
    override fun formatDay(epochMs: Long) = ""
    override suspend fun authorize(url: String, port: Int, path: String) = error("no browser in tests")
}
