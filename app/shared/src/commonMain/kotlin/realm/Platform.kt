package realm

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.Flow

/** Everything the shared UI needs from the device. Implemented by androidApp and desktopApp. */
interface Platform : Browser {
    /** Sent as the item source: "android", "desktop". */
    val name: String
    val prefs: Prefs

    /**
     * Strings kept on disk between runs, by key, as [prefs] are, for fetched data too big for prefs (each feed's
     * first page, the tiles' previews), so the app shows it at once the next time. The system may clear it.
     */
    val cache: Prefs

    /** Files/text arriving from outside: Android share sheet, desktop drag & drop. */
    val incoming: Flow<Incoming>

    /** Whether the app is on screen; background polling is skipped otherwise. */
    val isForeground: Boolean

    val canTakePhoto: Boolean
    suspend fun pickFiles(): List<LocalFile>
    suspend fun takePhoto(): LocalFile?

    /** Returns null if recording isn't possible (no mic / permission denied). */
    suspend fun startRecording(): Recording?

    /** Files/images on the clipboard, if any (for paste). Text paste is handled by the text field. */
    fun clipboardFiles(): List<LocalFile>
    fun copyText(text: String)

    /** Download into the user's downloads folder. Returns a short human message. */
    suspend fun save(url: String, auth: String?, name: String): String

    /** Download to a temp place and open with the system's default app. */
    suspend fun open(url: String, auth: String?, name: String)

    fun decodeImage(bytes: ByteArray, maxSize: Int): ImageBitmap?

    /** Image bytes kept on disk between runs, by key; null if not there. Blocking. */
    fun cachedImage(key: String): ByteArray?

    /** Keeps image bytes on disk for next time, dropping the least recently used past a size limit. Blocking. */
    fun cacheImage(key: String, bytes: ByteArray)
    fun formatTime(epochMs: Long): String
    fun formatDay(epochMs: Long): String
}

sealed interface Incoming {
    data class Files(val files: List<LocalFile>, val caption: String? = null) : Incoming
    data class Text(val text: String) : Incoming
}

interface Recording {
    /** Peak level 0..1 since the last call, for the little level meter. */
    fun level(): Float
    suspend fun stop(): LocalFile?
    fun cancel()
}
