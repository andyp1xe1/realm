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

    /** How this copy of the app replaces itself with a newer release; null when it can't, like a build from source. */
    val updater: Updater?

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

/** Replaces this copy of the app with a release of its fork. */
interface Updater {
    val version: AppVersion

    /** The newest stable release that's newer than this copy and has a file to replace it, as [newerApp] finds it. */
    suspend fun newer(): AppUpdate?

    /**
     * Downloads [update], telling [progress] how far it has got from 0 to 1, checks it against the release, then
     * tells [installing] and puts it in place, asking first where the system does. False when the user said no.
     */
    suspend fun install(update: AppUpdate, progress: (Float) -> Unit, installing: () -> Unit): Boolean

    /** Starts the installed update in place of the app running now. */
    fun restart()
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
