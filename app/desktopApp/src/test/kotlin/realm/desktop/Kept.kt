// Filmstrips of starting the app on what the last run kept: the feed and the overview shown at once, then
// refreshed in place; and the same with nothing kept, over a fast network and a slow one.
package realm.desktop

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import realm.Home
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * realm.jo.dev with the realms Main, Lisbon trip and Tax 2026, and Sam's share link at sam.dev, each read
 * taking [latency] ms of [clock], the clock the frames' animations run on, so a skeleton's delay is measured
 * against the same time. [milk] and [flight] are the texts of Main's and Lisbon trip's newest notes.
 */
private class Realms(val latency: Long, val milk: String = "buy oat milk, coffee beans", val flight: String = "flight TP1351 lands 9:40") {
    var clock: () -> Long = { 0 }

    private fun item(id: String, minutesAgo: Int, kind: String, text: String? = null, name: String? = null, mime: String? = null) =
        """{"id":"$id","kind":"$kind","text":${text?.let { "\"$it\"" }},"name":${name?.let { "\"$it\"" }},""" +
            """"mime":${mime?.let { "\"$it\"" }},"size":2400000,"src":"cli","created":${NOW - minutesAgo * 60_000L}}"""

    private fun page(vararg items: String) = """{"items":[${items.joinToString(",")}],"next":null}"""

    private fun answer(path: String): String? = when (path) {
        "/api/info" -> """{"version":3,"scope":"owner"}"""

        "/api/realms" -> """{"realms":[$REALMS]}"""

        "/api/realms/main/items" -> page(
            item("m4", 2, "note", milk),
            item("m3", 30, "link", "https://developers.cloudflare.com/r2/"),
            item("m2", 90, "file", "draft for review", "Q3-report-final-v2.pdf", "application/pdf"),
            item("m1", 200, "note", "ok"),
        )

        "/api/realms/trip/items" -> page(
            item("t2", 5, "note", flight),
            item("t1", 60, "file", null, "boarding-pass.pdf", "application/pdf"),
        )

        "/api/realms/tax/items" -> page(
            item("x2", 3000, "note", "ask about the home office deduction"),
            item("x1", 4000, "file", null, "receipts.zip"),
        )

        "/api/realms/main/links", "/api/links" -> """{"links":[]}"""

        "/s/k2/info" -> """{"version":3,"scope":"read","realm":{"id":"r","name":"Sam's recipes"}}"""

        "/s/k2/items" -> page(item("s1", 100, "note", "shakshuka: 6 eggs, 2 tins tomatoes, cumin, feta"))

        else -> null
    }

    val engine = MockEngine { req ->
        val due = clock() + latency
        while (clock() < due) delay(2)
        val body = answer(req.url.encodedPath)
        respond(
            body ?: """{"error":"not found"}""",
            if (body !=
                null
            ) {
                HttpStatusCode.OK
            } else {
                HttpStatusCode.NotFound
            },
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    companion object {
        /** Now, as every run of the stand-in sees it, so the same items are the same from run to run. */
        val NOW = System.currentTimeMillis()

        const val REALMS =
            """{"id":"main","name":"Main","created":1,"count":4},{"id":"trip","name":"Lisbon trip","created":2,"count":2},""" +
                """{"id":"tax","name":"Tax 2026","created":3,"count":2}"""

        /** Signed in to realm.jo.dev, with its realms and Sam's link known, Main open: what prefs keep. */
        val prefs = arrayOf(
            "server" to "https://realm.jo.dev",
            "token" to "t",
            "realms" to "[$REALMS]",
            "links" to """["https://sam.dev/s/k2"]""",
            "open" to "realm:main",
        )
    }
}

/** Every tile in [home] shows what it will: each link has said what it is, and each feed's latest items are there. */
private fun tilesKnown(home: Home) =
    home.shared.all { it.info != null } && home.spaces.filter { it.canRead }.all { home.preview(it).isNotEmpty() }

private fun feedKnown(home: Home) = home.model?.items?.isNotEmpty() == true

/** What a run kept in its cache; its first and last frames; and how many frames differed from the one before. */
private class Run(val kept: Map<String, String>, val first: BufferedImage, val last: BufferedImage, val moved: Int)

/**
 * A run on [server] from [cached], the cache the run before left, showing the overview if [tabs] else Main's feed,
 * as [name]-N; checks a skeleton or spinner showed only if it took longer than the delay.
 */
private fun start(name: String, server: Realms, cached: Map<String, String>, tabs: Boolean): Run {
    val platform = FakePlatform(*Realms.prefs, cached = cached)
    val home = Home(platform, CoroutineScope(Ui), server.engine)
    if (tabs) home.showTabs()
    val known = { if (tabs) tilesKnown(home) else feedKnown(home) }
    val film = Film(home)
    server.clock = { film.ms }
    var first: BufferedImage? = null
    var last: BufferedImage? = null
    var moved = 0
    fun frame(n: Int) {
        val png = film.frame().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
        val img = ImageIO.read(ByteArrayInputStream(png))
        last?.let { changed(it, img) }?.let { box ->
            if (moved++ < 3) println("$name: frame $n changed at $box")
        }
        if (first == null) first = img
        last = img
        if (n % 3 == 0) File(out, "$name-$n.png").writeBytes(png)
        Ui.pause(16)
    }
    var n = 0
    while (n < 300 && !(n > 0 && known())) frame(n++)
    val at = n
    repeat(40) { frame(n++) }
    film.close()
    check(known()) {
        val m = home.model
        "$name: never showed it all: ${m?.items?.size} items, loaded ${m?.loaded}, ${m?.message}, ${home.message}, previews ${home.previews.keys}"
    }
    film.checkIndicator(name, 0, at)
    return Run(platform.stored(), first!!, last!!, moved)
}

/**
 * Where [a] and [b] differ, as the box around every pixel that does, in dp of the 400×820 window; null if nowhere.
 * Not the composer at the bottom, where the caret blinks.
 */
private fun changed(a: BufferedImage, b: BufferedImage): List<Int>? {
    var box: IntArray? = null
    for (y in 0 until a.height - 2 * 80) {
        for (x in 0 until a.width) {
            if (a.getRGB(x, y) == b.getRGB(x, y)) continue
            val r = box ?: intArrayOf(x, y, x, y).also { box = it }
            r[0] = minOf(r[0], x)
            r[1] = minOf(r[1], y)
            r[2] = maxOf(r[2], x)
            r[3] = maxOf(r[3], y)
        }
    }
    return box?.map { it / 2 }
}

/**
 * kept-cold-feed-N, kept-cold-tabs-N: nothing kept, a fast network: blank, then the feed and the tiles fading in,
 * no skeleton. kept-slow-feed-N, kept-slow-tabs-N: the same at 700 ms a read: the bar fades in after the delay.
 * kept-warm-feed-N, kept-warm-tabs-N: the next run, from what that one kept: all there in the first frame, and
 * nothing moves as it refreshes. kept-changed-tabs-N, kept-changed-feed-N: the next, after a note changed in
 * Lisbon trip and one in Main: only that tile, and that row, fade through to it.
 */
internal fun keeping() {
    start("kept-cold-feed", Realms(40), emptyMap(), tabs = false)
    start("kept-cold-tabs", Realms(40), emptyMap(), tabs = true)
    start("kept-slow-feed", Realms(700), emptyMap(), tabs = false)
    val kept = start("kept-slow-tabs", Realms(700), emptyMap(), tabs = true).kept
    check(
        kept.keys.any { it.startsWith("feed/") } && kept.keys.any { it.startsWith("preview/") } &&
            kept.keys.any { it.startsWith("link-info/") },
    ) {
        "nothing was kept: ${kept.keys}"
    }
    println("kept: ${kept.keys.sorted()}")
    for (tabs in listOf(false, true)) {
        val name = if (tabs) "kept-warm-tabs" else "kept-warm-feed"
        val moved = start(name, Realms(40), kept, tabs).moved
        println("$name: $moved frames changed from the one before")
        check(moved == 0) { "$name: $moved frames changed as it refreshed what it showed already" }
    }
    for (tabs in listOf(true, false)) {
        val name = if (tabs) "kept-changed-tabs" else "kept-changed-feed"
        // Lisbon trip's newest note, for the overview; Main's, for its feed.
        val server = if (tabs) {
            Realms(
                40,
                flight = "flight TP1351 delayed: lands 10:25",
            )
        } else {
            Realms(40, milk = "buy oat milk, coffee beans and bread")
        }
        val run = start(name, server, kept, tabs)
        val box = changed(run.first, run.last)
        println("$name: what changed is at $box (left, top, right, bottom, dp)")
        check(box != null) { "$name: nothing changed" }
        // The overview's tiles are two to a row, ~180dp wide: one tile is all that changes. In the feed, one row.
        if (tabs) check(box[2] - box[0] < 200 && box[3] - box[1] < 260) { "$name: more than one tile changed: $box" }
        if (!tabs) check(box[3] - box[1] < 120) { "$name: more than one row changed: $box" }
    }
}
