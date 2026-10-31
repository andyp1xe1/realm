package realm.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.skia.EncodedImageFormat
import realm.Api
import realm.Bearer
import realm.Home
import realm.Item
import realm.Model
import realm.ui.Main
import realm.ui.RealmTheme
import realm.ui.SetupOrTabs
import java.io.File

/**
 * Frames of signing in from setup (login-N), against a stand-in server with two realms and nothing in them.
 * Numbered from the frame the realms first show, 16ms apart.
 */
internal fun login() {
    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { x ->
            val path = x.requestURI.path
            val body = when {
                path == "/api/info" -> """{"version":3,"scope":"owner","realm":null}"""

                path == "/api/realms" ->
                    """{"realms":[{"id":"main","name":"Main","created":1,"count":0},{"id":"trip","name":"Lisbon trip","created":2,"count":0}]}"""

                path.endsWith("/links") -> """{"links":[]}"""

                else -> """{"items":[],"next":null}"""
            }.toByteArray()
            x.responseHeaders.add("content-type", "application/json")
            x.sendResponseHeaders(200, body.size.toLong())
            x.responseBody.use { it.write(body) }
        }
        start()
    }
    val scope = CoroutineScope(Dispatchers.Default)
    val h = Home(FakePlatform(), scope)
    val scene = ImageComposeScene(400 * 2, 820 * 2, Density(2f)) {
        RealmTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { SetupOrTabs(h) } }
    }
    var t = 0L
    fun frame() = scene.render(t).also { t += 16_000_000L }

    repeat(20) { frame() }
    scope.launch { h.signIn("http://127.0.0.1:${server.address.port}", "t") }
    var waited = 0
    while (h.isEmpty && waited++ < 400) {
        frame()
        Thread.sleep(10)
    }
    for (n in 0..30) {
        val img = frame()
        if (n % 3 == 0) File(out, "login-$n.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    scene.close()
    server.stop(0)
    println("${out.path}/login-*.png")
    header()
    deploying()
}

/** Frames of an empty realm's top bar as its first item arrives (header-N): the agents move up into it. */
private fun header() {
    val m = Model(FakePlatform(), CoroutineScope(Dispatchers.Unconfined), Api("http://127.0.0.1:9/api", Bearer { "t" }, "desktop"))
    m.startEmpty()
    val scene = ImageComposeScene(400 * 2, 120 * 2, Density(2f)) {
        RealmTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { FeedPage(m, "Main", 2) } }
    }
    var t = 0L
    fun frame() = scene.render(t).also { t += 16_000_000L }
    repeat(60) { frame() }
    m.items.add(Item("first", "note", "hi", null, null, null, null, System.currentTimeMillis()))
    for (n in 0..30) {
        val img = frame()
        if (n % 2 == 0) File(out, "header-$n.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    scene.close()
    println("${out.path}/header-*.png")
}
