package realm.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import realm.Incoming
import realm.LocalFile
import realm.Platform
import realm.Prefs
import realm.Recording
import realm.jvm.DiskCache
import realm.jvm.Dates
import realm.jvm.JFile
import realm.jvm.download
import realm.jvm.uniqueFile
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class DesktopPlatform : Platform {
    override val name = "desktop"
    override val canTakePhoto = false
    override val isForeground = true
    var window: Frame? = null

    private val node = Preferences.userRoot().node("realm")
    override val prefs = object : Prefs {
        override fun get(key: String): String? = node.get(key, null)
        override fun set(key: String, value: String?) {
            if (value == null) node.remove(key) else node.put(key, value)
            node.flush()
        }
    }

    private val incomingChannel = Channel<Incoming>(Channel.UNLIMITED)
    override val incoming: Flow<Incoming> = incomingChannel.receiveAsFlow()

    /** Called from the window's AWT drop target. */
    fun drop(t: Transferable): Boolean {
        filesFrom(t).takeIf { it.isNotEmpty() }?.let { return incomingChannel.trySend(Incoming.Files(it)).isSuccess }
        if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            val s = t.getTransferData(DataFlavor.stringFlavor) as String
            if (s.isNotBlank()) return incomingChannel.trySend(Incoming.Text(s)).isSuccess
        }
        return false
    }

    private fun filesFrom(t: Transferable): List<LocalFile> = runCatching {
        when {
            t.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ->
                (t.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>().filter { it.isFile }.map { JFile(it) }
            t.isDataFlavorSupported(DataFlavor.imageFlavor) -> {
                val img = t.getTransferData(DataFlavor.imageFlavor) as java.awt.Image
                val buf = BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB)
                buf.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
                val f = File(tmp(), "pasted-${stamp()}.png")
                ImageIO.write(buf, "png", f)
                listOf(JFile(f, "image/png"))
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    override suspend fun pickFiles(): List<LocalFile> = withContext(Dispatchers.Main) {
        val files = if (gtkDialogSafe) {
            FileDialog(window, "Add to realm", FileDialog.LOAD).apply {
                isMultipleMode = true
                isVisible = true
            }.files.orEmpty().toList()
        } else {
            val chooser = JFileChooser().apply {
                isMultiSelectionEnabled = true
                dialogTitle = "Add to realm"
            }
            if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) chooser.selectedFiles.toList() else emptyList()
        }
        files.filter { it.isFile }.map { JFile(it) }
    }

    /**
     * On Linux the AWT file dialog is GTK's, and GLib aborts the whole JVM if GTK's settings schema
     * isn't installed (e.g. NixOS outside a wrapped environment). Look for it first; else use Swing's chooser.
     */
    private val gtkDialogSafe: Boolean by lazy {
        if ("linux" !in System.getProperty("os.name").lowercase()) return@lazy true
        val dataDirs = System.getenv("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() } ?: "/usr/local/share:/usr/share"
        val dataHome = System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share"
        val dirs = System.getenv("GSETTINGS_SCHEMA_DIR")?.split(':').orEmpty() +
            (dataDirs.split(':') + dataHome).map { "$it/glib-2.0/schemas" }
        val needle = "org.gtk.Settings.FileChooser".toByteArray()
        dirs.any { dir ->
            val f = File(dir, "gschemas.compiled")
            f.isFile && runCatching { f.readBytes().indexOf(needle) >= 0 }.getOrDefault(false)
        }
    }

    override suspend fun takePhoto(): LocalFile? = null

    override suspend fun startRecording(): Recording? = withContext(Dispatchers.IO) {
        runCatching { DesktopRecording(File(tmp(), "voice-${stamp()}.wav")) }.getOrNull()
    }

    override fun clipboardFiles(): List<LocalFile> =
        runCatching { filesFrom(Toolkit.getDefaultToolkit().systemClipboard.getContents(null)) }.getOrDefault(emptyList())

    override fun copyText(text: String) {
        val s = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(s, s)
    }

    override suspend fun save(url: String, auth: String?, name: String): String = withContext(Dispatchers.IO) {
        val home = File(System.getProperty("user.home"))
        val dir = File(home, "Downloads").takeIf { it.isDirectory } ?: home
        val dest = uniqueFile(dir, name)
        download(url, auth, dest)
        "Saved to ${dest.path.replaceFirst(home.path, "~")}"
    }

    override suspend fun open(url: String, auth: String?, name: String) = withContext(Dispatchers.IO) {
        val file = File(tmp(), "open/${url.hashCode().toUInt()}/$name")
        if (!file.exists()) download(url, auth, file)
        val canOpen = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)
        if (!canOpen || runCatching { Desktop.getDesktop().open(file) }.isFailure) ProcessBuilder("xdg-open", file.path).start()
        Unit
    }

    private val imageCache = DiskCache(File(cacheHome(), "images"), 300L * 1024 * 1024)
    override fun cachedImage(key: String) = imageCache.get(key)
    override fun cacheImage(key: String, bytes: ByteArray) = imageCache.put(key, bytes)

    /** The system's place for caches: XDG on Linux, Library/Caches on macOS, LocalAppData on Windows. */
    private fun cacheHome(): File {
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        return when {
            "mac" in os -> File(home, "Library/Caches/realm")
            "win" in os -> File(System.getenv("LOCALAPPDATA") ?: home, "realm/cache")
            else -> File(System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.cache", "realm")
        }
    }

    override fun decodeImage(bytes: ByteArray, maxSize: Int): ImageBitmap? = runCatching {
        val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
        val img = Image.makeFromEncoded(bytes)
        val quarterTurns = when (codec.encodedOrigin) {
            EncodedOrigin.RIGHT_TOP -> 1
            EncodedOrigin.BOTTOM_RIGHT -> 2
            EncodedOrigin.LEFT_BOTTOM -> 3
            else -> 0
        }
        val scale = min(1f, maxSize * 1.5f / max(img.width, img.height))
        if (quarterTurns == 0 && scale == 1f) return@runCatching img.toComposeImageBitmap()
        val w = (img.width * scale).toInt()
        val h = (img.height * scale).toInt()
        val (ow, oh) = if (quarterTurns % 2 == 1) h to w else w to h
        val surface = Surface.makeRasterN32Premul(ow, oh)
        surface.canvas.apply {
            translate(ow / 2f, oh / 2f)
            rotate(90f * quarterTurns)
            scale(scale, scale)
            drawImage(img, -img.width / 2f, -img.height / 2f)
        }
        surface.makeImageSnapshot().toComposeImageBitmap()
    }.getOrNull()

    override fun formatTime(epochMs: Long) = Dates.time(epochMs)
    override fun formatDay(epochMs: Long) = Dates.day(epochMs)

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun tmp() = File(System.getProperty("java.io.tmpdir"), "realm").apply { mkdirs() }
    private fun stamp() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
}

/** 16 kHz mono WAV straight from the default microphone. */
private class DesktopRecording(private val file: File) : Recording {
    private val format = AudioFormat(16_000f, 16, 1, true, false)
    private val line: TargetDataLine = AudioSystem.getTargetDataLine(format).apply { open(format); start() }
    private val out = ByteArrayOutputStream()
    @Volatile private var running = true
    @Volatile private var peak = 0f

    private val reader = thread(isDaemon = true, name = "realm-mic") {
        val buf = ByteArray(3200)
        while (running) {
            val n = line.read(buf, 0, buf.size)
            if (n <= 0) continue
            synchronized(out) { out.write(buf, 0, n) }
            var p = 0
            for (i in 0 until n - 1 step 2) p = max(p, abs((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)))
            peak = max(peak, p / 32768f)
        }
    }

    override fun level(): Float = sqrt(peak).also { peak = 0f }

    private fun halt() {
        running = false
        line.stop()
        line.close()
        reader.join(500)
    }

    override suspend fun stop(): LocalFile? = withContext(Dispatchers.IO) {
        halt()
        val bytes = synchronized(out) { out.toByteArray() }
        if (bytes.isEmpty()) return@withContext null
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(bytes), format, bytes.size / 2L), AudioFileFormat.Type.WAVE, file)
        JFile(file, "audio/wav")
    }

    override fun cancel() = halt()
}
