package realm.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.skia.EncodedImageFormat
import realm.Api
import realm.Bearer
import realm.Item
import realm.Model
import realm.ui.Feed
import realm.ui.RealmTheme
import java.io.File

/**
 * send-N: a note sent to a stand-in server that takes 300 ms to answer, under two older notes. Checks the
 * newest row keeps its key from sending to sent, so it stays put instead of being drawn again.
 */
internal fun sending() {
    val now = System.currentTimeMillis()
    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { x ->
            val body = when {
                x.requestMethod == "POST" -> {
                    Thread.sleep(300)
                    """{"id":"n3","kind":"note","text":"see you at 8","created":$now}"""
                }

                x.requestURI.path.endsWith("/links") -> """{"links":[]}"""

                else -> """{"items":[],"next":null}"""
            }.toByteArray()
            x.responseHeaders.add("content-type", "application/json")
            x.sendResponseHeaders(if (x.requestMethod == "POST") 201 else 200, body.size.toLong())
            x.responseBody.use { it.write(body) }
        }
        start()
    }
    val m = Model(FakePlatform(), CoroutineScope(Ui), Api("http://127.0.0.1:${server.address.port}/api", Bearer { "t" }, "desktop"))
    m.startEmpty()
    m.items.addAll(
        listOf(
            Item("n2", "note", "on my way", created = now - 60_000),
            Item("n1", "note", "dinner tonight?", created = now - 120_000),
        ),
    )
    val scene = ImageComposeScene(400 * 2, 500 * 2, Density(2f)) {
        RealmTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Feed(m, Modifier.fillMaxSize()) } }
    }
    var t = 0L
    val newest = mutableListOf<Any>()
    fun frame(n: Int?) {
        val img = scene.renderOnUi(t)
        t += 16_000_000L
        if (n != null) {
            m.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 1 }?.let { newest += it.key }
            if (n % 3 == 0) File(out, "send-$n.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        Ui.pause(16)
    }
    repeat(20) { frame(null) }
    m.send("see you at 8")
    var n = 0
    while (n < 300 && (m.pending.isNotEmpty() || m.items.firstOrNull()?.id != "n3")) frame(n++)
    repeat(30) { frame(n++) }
    scene.close()
    server.stop(0)
    val keys = newest.distinct()
    println("send: the newest row was drawn under ${keys.size} key(s): $keys")
    check(m.items.first().id == "n3") { "send: the note never landed" }
    check(keys.size == 1) { "send: the sent note's row changed key as it landed: $keys" }
    println("${out.path}/send-*.png")
}
