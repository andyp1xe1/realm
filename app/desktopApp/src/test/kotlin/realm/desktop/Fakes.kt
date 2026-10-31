// Fake platform pieces and the screenshot helper for Preview.kt.
package realm.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.skia.EncodedImageFormat
import realm.Api
import realm.Bearer
import realm.ByteReader
import realm.Home
import realm.Info
import realm.Item
import realm.LocalFile
import realm.Model
import realm.Pending
import realm.Platform
import realm.Prefs
import realm.Realm
import realm.Recording
import realm.Space
import realm.jvm.Dates
import realm.jvm.JFile
import realm.ui.Composer
import realm.ui.Feed
import realm.ui.Grid
import realm.ui.Main
import realm.ui.RealmTheme
import realm.ui.RecordingPanel
import realm.ui.Setup
import realm.ui.ShareButton
import realm.ui.ShareSheet
import realm.ui.Tabs
import realm.ui.TopBar
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sin

internal val real by lazy { DesktopPlatform() }

internal class FakePlatform(vararg prefs: Pair<String, String>) : Platform {
    override val name = "desktop"
    private val map = mutableMapOf<String, String?>(*prefs)
    override val prefs = object : Prefs {
        override fun get(key: String) = map[key]
        override fun set(key: String, value: String?) {
            map[key] = value
        }
    }
    override val incoming = emptyFlow<realm.Incoming>()
    override val isForeground = false
    override val canTakePhoto = true
    override suspend fun pickFiles() = emptyList<LocalFile>()
    override suspend fun takePhoto(): LocalFile? = null
    override suspend fun startRecording(): Recording? = FakeRecording()
    override fun clipboardFiles() = emptyList<LocalFile>()
    override fun copyText(text: String) {}
    override suspend fun save(url: String, auth: String?, name: String) = ""
    override suspend fun open(url: String, auth: String?, name: String) {}

    /** Consents at once, as if the user had clicked through Cloudflare's screen. */
    override suspend fun authorize(url: String, port: Int, path: String) =
        mapOf("code" to "preview", "state" to io.ktor.http.Url(url).parameters["state"].orEmpty())
    override fun decodeImage(bytes: ByteArray, maxSize: Int): ImageBitmap? = real.decodeImage(bytes, maxSize)
    override fun cachedImage(key: String): ByteArray? = null
    override fun cacheImage(key: String, bytes: ByteArray) {}
    override fun formatTime(epochMs: Long) = Dates.time(epochMs)
    override fun formatDay(epochMs: Long) = Dates.day(epochMs)
}

internal class FakeFile(override val name: String, override val size: Long, override val mime: String?) : LocalFile {
    override fun reader(): ByteReader = error("preview")
}

internal class FakeRecording : Recording {
    private val t0 = System.nanoTime()
    override fun level(): Float {
        val t = (System.nanoTime() - t0) / 1e9
        // Speech-ish: syllable bursts with pauses.
        return (abs(sin(t * 7)) * (0.35 + 0.65 * abs(sin(t * 1.3))) * (0.8 + 0.2 * Math.random())).toFloat()
    }
    override suspend fun stop(): LocalFile? = null
    override fun cancel() {}
}

internal lateinit var out: File

internal fun shot(name: String, wDp: Int, hDp: Int, dark: Boolean = true, realtimeMs: Long = 0, content: @Composable () -> Unit) {
    val scene = ImageComposeScene(wDp * 2, hDp * 2, Density(2f)) {
        RealmTheme(dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
    }
    for (f in 0..30) scene.render(f * 16_000_000L)
    // Some content (the recording waveform) samples on a real-time clock; let it run.
    val t0 = System.nanoTime()
    while (System.nanoTime() - t0 < realtimeMs * 1_000_000) {
        Thread.sleep(16)
        scene.render(System.nanoTime() - t0 + 600_000_000L)
    }
    val png = scene.render(600_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
    File(out, "$name.png").writeBytes(png)
    scene.close()
    println("${out.path}/$name.png")
}

/** A feed as the app shows it, under the top bar. */
@Composable
internal fun FeedPage(m: Model, title: String, tabs: Int) = Column {
    TopBar(title, m, tabs, onTabs = {}, onSettings = {})
    Main(m, title)
}

/** The overview as the app shows it, under the top bar. */
@Composable
internal fun GridPage(home: Home) = Column {
    TopBar(null, null, home.spaces.size, onTabs = {}, onSettings = {})
    Grid(home)
}
