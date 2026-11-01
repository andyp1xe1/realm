// Renders app screens offscreen with fake data: `./gradlew :desktopApp:preview` → desktopApp/build/preview/*.png
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

private fun model(): Model {
    // Unreachable server: nothing is fetched, we fill the lists by hand.
    val m = Model(FakePlatform(), CoroutineScope(Dispatchers.Unconfined), Api("http://127.0.0.1:9/api", Bearer { "t" }, "desktop"))
    m.startEmpty() // loaded, as if fetched: the lists below are its first page
    val now = System.currentTimeMillis()
    fun item(
        i: Int,
        kind: String,
        text: String? = null,
        name: String? = null,
        mime: String? = null,
        size: Long? = null,
        src: String? = null,
    ) = Item("id$i", kind, text, name, mime, size, src, now - i * 600_000L)
    m.items.addAll(
        listOf(
            item(1, "note", "ok"),
            item(2, "file", "draft for review", "Q3-report-final-v2.pdf", "application/pdf", 2_400_000),
            item(3, "audio", null, "voice_20261030.m4a", "audio/mp4", 180_000, src = "android"),
            item(4, "link", "https://developers.cloudflare.com/r2/"),
            item(5, "note", "buy oat milk, coffee beans\nand call the landlord about the heating", src = "cli"),
            item(6, "note", "hi"),
        ),
    )
    m.pending.addAll(
        listOf(
            Pending(1, null, FakeFile("holiday-video.mp4", 734_000_000, "video/mp4"), "from the trip").apply { progress = 0.42f },
            Pending(2, null, FakeFile("notes.zip", 12_000_000, "application/zip"), null).apply { error = "link used up" },
            Pending(3, "short", null, null),
        ),
    )
    return m
}

/** Signed in to an unreachable server, with realms, connected links, and tile previews filled in by hand. */
private fun home(feed: Model): Home {
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
    val sam = "https://realm.sam.dev/s/k2"
    val file = "https://realm.sam.dev/s/f9"
    val drop = "https://realm.jo.dev/s/d4"
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

/** Frames from the feed zooming out into the tab overview (zoom-N, about N×16ms in; zoom-0 is before), then back (unzoom-N). */
private fun zoom(h: Home) {
    h.open(h.spaces.first())
    val scene = ImageComposeScene(400 * 2, 820 * 2, Density(2f)) {
        RealmTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Tabs(h) } }
    }
    var t = 0L
    fun frame() = scene.render(t).also { t += 16_000_000L }
    repeat(30) { frame() }
    File(out, "zoom-0.png").writeBytes(frame().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    h.showTabs()
    for (n in 1..40) {
        val img = frame()
        if (n % 3 == 0 || n == 40) File(out, "zoom-$n.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    // And back into the feed: unzoom-N.
    h.hideTabs()
    for (n in 1..30) {
        val img = frame()
        if (n % 3 == 0) File(out, "unzoom-$n.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    scene.close()
    println("${out.path}/zoom-*.png")
}

/**
 * Frames of the composer switching modes inside a feed: mode-mic-N after tapping the mic, mode-expand-N after
 * tapping expand, mode-collapse-N after shrinking it again. N is frames of about 16ms; 0 is just before.
 */
private fun modes() {
    for (which in listOf("mic", "expand")) {
        val m = model().apply { pending.clear() }
        val scene = ImageComposeScene(400 * 2, 820 * 2, Density(2f)) {
            RealmTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { FeedPage(m, "Main", 7) } }
        }
        var t = 0L
        fun frame() = scene.render(t).also { t += 16_000_000L }
        repeat(30) { frame() }
        File(out, "mode-$which-0.png").writeBytes(frame().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        if (which == "mic") {
            // The mic sits at the composer's right end.
            val at = androidx.compose.ui.geometry.Offset(364f * 2, 790f * 2)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at)
        } else {
            // The expand button, next to the mic. Short text, so closing it again sticks.
            m.incomingText = "a short note"
            repeat(5) { frame() }
            val at = androidx.compose.ui.geometry.Offset(315f * 2, 783f * 2)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at)
        }
        for (n in 1..30) {
            val img = frame()
            if (n in setOf(3, 5, 8, 12, 30)) File(out, "mode-$which-$n.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        if (which == "expand") {
            // And closed again with the shrink button in the editor's top-right corner.
            val at = androidx.compose.ui.geometry.Offset(362f * 2, 90f * 2)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at)
            for (n in 1..30) {
                val img = frame()
                if (n in setOf(3, 8, 12, 30)) File(out, "mode-collapse-$n.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
        scene.close()
    }
    println("${out.path}/mode-*.png")
    arrive()
}

/** Frames of a feed getting its first page (arrive-page-N), then one new item (arrive-one-N). */
private fun arrive() {
    val full = model().apply { pending.clear() }
    val m = model().apply {
        pending.clear()
        items.clear()
    }
    val scene = ImageComposeScene(400 * 2, 820 * 2, Density(2f)) {
        RealmTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Screen(m) } }
    }
    var t = 0L
    fun frame() = scene.render(t).also { t += 16_000_000L }
    fun shots(name: String) {
        for (n in 0..20) {
            val img = frame()
            if (n in setOf(0, 4, 8, 20)) File(out, "arrive-$name-$n.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
    repeat(10) { frame() }
    m.items.addAll(full.items.drop(1))
    shots("page")
    m.items.add(0, full.items[0].copy(id = "zz-new", text = "just arrived from the phone"))
    shots("one")
    scene.close()
    println("${out.path}/arrive-*.png")
    create()
}

/**
 * Frames of making a realm from the tab grid (create-N), against a stand-in server that keeps a list of
 * realms, adds one on POST /api/realms, and has no items or links anywhere.
 */
private fun create() {
    val realms = mutableListOf(
        """{"id":"main","name":"Main","created":1,"count":3}""",
        """{"id":"trip","name":"Lisbon trip","created":2,"count":2}""",
    )
    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { x ->
            val path = x.requestURI.path
            val body = when {
                path == "/api/info" -> """{"version":3,"scope":"owner","realm":null}"""

                path == "/api/realms" && x.requestMethod == "POST" ->
                    """{"id":"ideas","name":"Ideas","created":3,"count":0}""".also { realms += it }

                path == "/api/realms" -> """{"realms":[${realms.joinToString(",")}]}"""

                x.requestMethod == "DELETE" -> {
                    realms.removeAll { "\"${path.substringAfterLast('/')}\"" in it }
                    """{"ok":true}"""
                }

                path.endsWith("/links") -> """{"links":[]}"""

                else -> """{"items":[],"next":null}"""
            }.toByteArray()
            x.responseHeaders.add("content-type", "application/json")
            x.sendResponseHeaders(if (x.requestMethod == "POST") 201 else 200, body.size.toLong())
            x.responseBody.use { it.write(body) }
        }
        start()
    }
    val h = Home(FakePlatform("server" to "http://127.0.0.1:${server.address.port}", "token" to "t"), CoroutineScope(Dispatchers.Default))
    val scene = ImageComposeScene(400 * 2, 820 * 2, Density(2f)) {
        RealmTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Tabs(h) } }
    }
    var t = 0L
    fun frame() = scene.render(t).also { t += 16_000_000L }

    // Let the realms load and Main open, then go to the grid. Waits on the stand-in server, not a frame count.
    fun until(ok: () -> Boolean) {
        repeat(400) {
            if (ok()) return
            frame()
            Thread.sleep(10)
        }
    }
    until { h.realms.size >= 2 && h.model != null }
    repeat(20) { frame() }
    h.showTabs()
    repeat(40) {
        frame()
        Thread.sleep(5)
    }
    h.createRealm("Ideas")
    for (n in 0..80) {
        val img = frame()
        Thread.sleep(5) // real time for the stand-in server to answer
        if (n % 4 == 0) File(out, "create-$n.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    // Then back to the grid and delete Lisbon trip: it fades away and Ideas slides into its place (delete-N).
    h.showTabs()
    repeat(40) {
        frame()
        Thread.sleep(5)
    }
    until { h.realms.any { it.id == "ideas" } }
    h.deleteRealm(h.realms.first { it.id == "trip" })
    for (n in 0..40) {
        val img = frame()
        Thread.sleep(5)
        if (n % 4 == 0) File(out, "delete-$n.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    scene.close()
    server.stop(0)
    println("${out.path}/create-*.png")
    login()
}

/** Same arrangement as the real screen: the composer floats over the bottom of the feed. */
@Composable
private fun Screen(m: Model) {
    var h by remember { mutableStateOf(72.dp) }
    Box(Modifier.fillMaxSize()) {
        Feed(m, Modifier.fillMaxSize(), bottomPadding = h)
        Composer(m, Modifier.align(Alignment.BottomCenter), onCompactHeight = { h = it })
    }
}

fun main(args: Array<String>) {
    out = File(args.firstOrNull() ?: "build/preview").apply { mkdirs() }
    // A failed check ends the run, though the stand-in homes' threads would keep it alive.
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        e.printStackTrace()
        kotlin.system.exitProcess(1)
    }
    val m = model()
    for ((label, w, h) in listOf(Triple("phone", 400, 820), Triple("desktop", 880, 640))) {
        shot("feed-$label", w, h) {
            Screen(m)
        }
    }
    // The README screenshot: the whole app, top bar included.
    // No uploads in flight; realtimeMs lets the fetch from the unreachable server fail so the loading bar is gone.
    val clean = model().apply { pending.clear() }
    shot("app", 400, 760, realtimeMs = 1500) { FeedPage(clean, "Main", 7) }
    shot("feed-light", 400, 820, dark = false) {
        Screen(m)
    }
    val editing = model().apply { editing = items[4] }
    shot("composer-editing", 400, 300) { Screen(editing) }
    val long = model().apply {
        items.add(
            0,
            items[4].copy(
                id = "long",
                text = (1..9).joinToString("\n") {
                    "line $it of a longer note that needs room to breathe"
                },
            ),
        )
        this.editing = items[0]
    }
    shot("composer-expanded", 400, 820) { Screen(long) }
    // Staged attachments: a real image (for the thumbnail) and two plain files.
    val png = File(out, "staged.png.src").apply {
        val img = BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 320) for (y in 0 until 200) img.setRGB(x, y, java.awt.Color(60 + x / 3, 40 + y / 3, 160 + x / 4).rgb)
        ImageIO.write(img, "png", this)
    }
    val withFiles = model().apply {
        pending.clear()
        staged.addAll(
            listOf(
                JFile(png, "image/png", "sunset.png"),
                FakeFile("Q3-report.pdf", 2_400_000, "application/pdf"),
                FakeFile("voice.m4a", 180_000, "audio/mp4"),
            ),
        )
    }
    shot("composer-staged", 400, 420, realtimeMs = 500) { Screen(withFiles) }
    val withFilesLong = model().apply {
        pending.clear()
        staged.addAll(withFiles.staged)
        items.add(0, items[4].copy(id = "long", text = (1..9).joinToString("\n") { "line $it of a caption" }))
        this.editing = null
        incomingText = items[0].text
    }
    shot("composer-staged-expanded", 400, 820) { Screen(withFilesLong) }
    shot("recording", 400, 220, realtimeMs = 2500) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.padding(8.dp),
            ) {
                RecordingPanel(FakeRecording(), onCancel = {}, onSend = {})
            }
        }
    }
    // Hovered: the pointer over the stacked pill, and over the already fanned-out one.
    for ((name, expanded) in listOf("agents-hover" to false, "agents-hover-expanded" to true)) {
        val scene = ImageComposeScene(200 * 2, 50 * 2, Density(2f)) {
            RealmTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.padding(8.dp)) { ShareButton(onClick = {}, expanded = expanded) }
                }
            }
        }
        scene.render(0)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter, androidx.compose.ui.geometry.Offset(60f, 40f))
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.geometry.Offset(62f, 40f))
        for (f in 1..40) scene.render(f * 16_000_000L)
        File(out, "$name.png").writeBytes(scene.render(700_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
    shot("agents", 200, 80) {
        androidx.compose.foundation.layout.Column(Modifier.padding(8.dp)) {
            ShareButton(onClick = {})
            ShareButton(onClick = {}, expanded = true)
        }
    }
    val owned = Api("https://realm.example.dev/api", Bearer { "t" }, "desktop")
    val sharing = Model(FakePlatform(), CoroutineScope(Dispatchers.Unconfined), owned).apply {
        val day = System.currentTimeMillis() + 86_400_000
        links.set(
            listOf(
                realm.Link("a1", "full", url = "https://realm.example.dev/s/a1", expires = day),
                realm.Link("b2", "read", label = "agent", url = "https://realm.example.dev/s/b2", expires = day, uses = 3),
            ),
        )
    }
    // share-skeleton: the links not known yet, from a server that can't be reached: room kept for a couple.
    val unknown = Model(FakePlatform(), CoroutineScope(Dispatchers.Unconfined), Api("http://127.0.0.1:9/api", Bearer { "t" }, "desktop"))
    shot("share-skeleton", 460, 510) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
            androidx.compose.foundation.layout.Column(Modifier.padding(20.dp)) { ShareSheet(unknown, {}) }
        }
    }
    for ((name, url, h) in listOf(Triple("share-form", null, 510), Triple("share-copy", "https://realm.example.dev/s/a1", 430))) {
        shot(name, 460, h) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
                androidx.compose.foundation.layout.Column(Modifier.padding(20.dp)) { ShareSheet(sharing, {}, initialUrl = url) }
            }
        }
    }
    // A realm the server says has nothing in it, from a stand-in server that answers every list with no items.
    val none = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { x ->
            val body = """{"items":[],"next":null}""".toByteArray()
            x.responseHeaders.add("content-type", "application/json")
            x.sendResponseHeaders(200, body.size.toLong())
            x.responseBody.use { it.write(body) }
        }
        start()
    }
    val empty =
        Model(
            FakePlatform(),
            CoroutineScope(Dispatchers.Unconfined),
            Api(
                "http://127.0.0.1:${none.address.port}/api",
                Bearer {
                    "t"
                },
                "desktop",
            ),
        )
    shot("empty", 400, 760, realtimeMs = 1500) { FeedPage(empty, "Ideas", 7) }
    shot("empty-light", 400, 760, dark = false, realtimeMs = 1500) { FeedPage(empty, "Ideas", 7) }
    shot("setup", 400, 760) { Setup(Home(FakePlatform(), CoroutineScope(Dispatchers.Unconfined))) }
    shot("setup-light", 400, 760, dark = false) { Setup(Home(FakePlatform(), CoroutineScope(Dispatchers.Unconfined))) }
    shot("setup-token", 460, 440) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
            androidx.compose.foundation.layout.Column(Modifier.padding(20.dp)) {
                realm.ui.TokenSheetPreview(Home(FakePlatform(), CoroutineScope(Dispatchers.Unconfined)))
            }
        }
    }
    val home = home(m)
    for ((label, w, h) in listOf(Triple("phone", 400, 820), Triple("desktop", 880, 640))) {
        shot("grid-$label", w, h, realtimeMs = 1500) { GridPage(home) }
    }
    shot("grid-light", 400, 820, dark = false) { GridPage(home) }
    zoom(
        home(m).also {
            it.modelOf(it.spaces.first()).apply {
                startEmpty() // loaded, as if fetched: its first page is the feed's
                items.addAll(m.items)
            }
        },
    )
    modes()
    kotlin.system.exitProcess(0)
}
