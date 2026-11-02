package realm

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.Flow

/** Everything the shared UI needs from the device. Implemented by androidApp and desktopApp. */
interface Platform : Browser {
    /** Sent as the item source: "android", "desktop". */
    val name: String
    val prefs: Prefs

    /** Strings kept on disk between runs, by key, for fetched data too big for [prefs]. The system may clear it. */
    val cache: Prefs

    /** Files/text arriving from outside: Android share sheet, desktop drag & drop. */
    val incoming: Flow<Incoming>

    val isForeground: Boolean

    val canTakePhoto: Boolean
    suspend fun pickFiles(): List<LocalFile>
    suspend fun takePhoto(): LocalFile?

    /** Null if recording isn't possible (no mic / permission denied). */
    suspend fun startRecording(): Recording?

    /** Files/images on the clipboard, if any. Text paste is handled by the text field. */
    fun clipboardFiles(): List<LocalFile>
    fun copyText(text: String)

    /** Downloads into the user's downloads folder; returns a short message for the user. */
    suspend fun save(url: String, auth: String?, name: String): String

    /** Downloads to a temp place and opens it with the system's default app. */
    suspend fun open(url: String, auth: String?, name: String)

    fun decodeImage(bytes: ByteArray, maxSize: Int): ImageBitmap?

    /** Image bytes kept on disk between runs, by key; null if not there. Blocking. */
    fun cachedImage(key: String): ByteArray?

    /** Keeps image bytes on disk, dropping the least recently used past a size limit. Blocking. */
    fun cacheImage(key: String, bytes: ByteArray)
    fun formatTime(epochMs: Long): String
    fun formatDay(epochMs: Long): String
}

sealed interface Incoming {
    data class Files(val files: List<LocalFile>, val caption: String? = null) : Incoming
    data class Text(val text: String) : Incoming
}

interface Recording {
    /** Peak level 0..1 since the last call. */
    fun level(): Float
    suspend fun stop(): LocalFile?
    fun cancel()
}
