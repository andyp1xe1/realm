// Stand-in homes for the previews: realms, connected links and tile previews, as if fetched.
package realm.desktop

import androidx.compose.foundation.layout.Box
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
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sin

/** A stand-in for other people's servers: the share links k2 (Sam's recipes), f9 (one file) and d4 (a drop link). */
private fun linkServer(now: Long): String {
    fun item(id: String, kind: String, text: String? = null, name: String? = null, mime: String? = null) =
        """{"id":"$id","kind":"$kind","text":${text?.let { "\"$it\"" }},"name":${name?.let { "\"$it\"" }},""" +
            """"mime":${mime?.let { "\"$it\"" }},"size":1000,"src":null,"created":$now}"""
    val lease = item("f", "file", name = "lease-draft.pdf", mime = "application/pdf")
    val answers = mapOf(
        "/s/k2/info" to """{"version":3,"scope":"read","realm":{"id":"r","name":"Sam's recipes"}}""",
        "/s/k2/items" to """{"items":[${item("s1", "note", "shakshuka: 6 eggs, 2 tins tomatoes, cumin, feta")},""" +
            """${item("s2", "link", "https://cooking.nytimes.com/recipes/1014721")}],"next":null}""",
        "/s/f9/info" to """{"version":3,"scope":"read","realm":{"id":"f","name":"Flat hunt"},"items":1,"item":$lease}""",
        "/s/f9/items" to """{"items":[$lease],"next":null}""",
        "/s/d4/info" to """{"version":3,"scope":"drop","realm":{"id":"d","name":"Jo's inbox"}}""",
    )
    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { x ->
            val body = answers[x.requestURI.path]
            val bytes = (body ?: """{"error":"not found"}""").toByteArray()
            x.responseHeaders.add("content-type", "application/json")
            x.sendResponseHeaders(if (body != null) 200 else 404, bytes.size.toLong())
            x.responseBody.use { it.write(bytes) }
        }
        start()
    }
    return "http://127.0.0.1:${server.address.port}"
}

/** Signed in to an unreachable server, with realms, connected links, and tile previews filled in by hand. */
internal fun home(feed: Model): Home {
    val h = Home(FakePlatform("server" to "http://127.0.0.1:9", "token" to "t"), CoroutineScope(Dispatchers.Unconfined))
    val now = System.currentTimeMillis()
    fun item(id: String, kind: String, text: String? = null, name: String? = null, mime: String? = null) =
        Item(id, kind, text, name, mime, 1000, null, now)
    h.realms += listOf(
        Realm("main", "Main", now, 214, now),
        Realm("trip", "Lisbon trip", now, 37, now - 86_400_000),
        Realm("tax", "Tax 2026", now, 12, now - 9 * 86_400_000),
        Realm("new", "Ideas", now, 0, null),
    )
    h.previews["realm:main"] = feed.items
    h.previews["realm:trip"] = listOf(
        item("t1", "note", "flight TP1351 lands 9:40, taxi rank is left of arrivals"),
        item("t2", "file", name = "boarding-pass.pdf", mime = "application/pdf"),
        item("t3", "link", "https://www.timeout.com/lisbon/restaurants"),
    )
    h.previews["realm:tax"] = listOf(
        item("x1", "file", name = "invoice-0423.pdf", mime = "application/pdf"),
        item("x2", "file", name = "receipts.zip", mime = "application/zip"),
        item("x3", "note", "ask about the home office deduction"),
    )
    // The links answer from a stand-in server, so refreshing them in the overview finds them as set up here.
    val links = linkServer(now)
    val sam = "$links/s/k2"
    val file = "$links/s/f9"
    val drop = "$links/s/d4"
    h.shared += Space.Shared(sam, Info(scope = "read", realm = Realm("r", "Sam's recipes")), null, Api(sam, null, "desktop"))
    h.shared +=
        Space.Shared(
            file,
            Info(
                scope = "read",
                realm = Realm("f", "Flat hunt"),
                items = 1,
                item = item("f", "file", name = "lease-draft.pdf", mime = "application/pdf"),
            ),
            null,
            Api(file, null, "desktop"),
        )
    h.shared += Space.Shared(drop, Info(scope = "drop", realm = Realm("d", "Jo's inbox")), null, Api(drop, null, "desktop"))
    h.previews["link:$file"] = listOf(item("f", "file", name = "lease-draft.pdf", mime = "application/pdf"))
    h.previews["link:$sam"] = listOf(
        item("s1", "note", "shakshuka: 6 eggs, 2 tins tomatoes, cumin, feta"),
        item("s2", "link", "https://cooking.nytimes.com/recipes/1014721"),
    )
    h.open(h.spaces.first())
    h.showTabs()
    return h
}
