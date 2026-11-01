package realm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What the app keeps of the realms, the tiles and the links between runs, and shows at once the next time. */
class KeptTest {
    private val link = "https://sam.dev/s/k2"
    private val note = Item("b", "note", "buy oat milk", created = 1)
    private val photo = Item("a", "image", null, "lisbon.jpg", "image/jpeg", 200_000, "android", 2)
    private val sams = Info(scope = "read", realm = Realm("r", "Sam's recipes"), usesLeft = 3)

    /** Signed in to realm.jo.dev with its realm Main open, and Sam's link connected, as the last run left it. */
    private val prefs = MapPrefs(
        "server" to "https://realm.jo.dev",
        "token" to "t",
        "realms" to """[{"id":"main","name":"Main","count":2}]""",
        "links" to """["$link"]""",
        "open" to "realm:main",
    )

    /** What the last run kept in the cache: Main's first page and tile, and what Sam's link said it was. */
    private val cache = MapPrefs().apply {
        FeedCache(this, "realm:main").keepFirst(Page(listOf(photo, note)))
        set("preview/realm:main", json.encodeToString(ListSerializer(Item.serializer()), listOf(photo, note)))
        set("link-info/$link", json.encodeToString(Info.serializer(), sams))
    }

    /** realm.jo.dev, where Main now starts with a new note, and Sam's link, the same as it was; both wait for [open]. */
    private class Servers(scope: TestScope, val main: List<Item>) {
        val open = CompletableDeferred<Unit>()
        val engine = MockEngine(
            MockEngineConfig().apply { dispatcher = StandardTestDispatcher(scope.testScheduler) }.also {
                it.addHandler { req ->
                    open.await()
                    val body = when (req.url.host + req.url.encodedPath) {
                        "realm.jo.dev/api/realms" -> """{"realms":[{"id":"main","name":"Main","count":3}]}"""

                        "realm.jo.dev/api/realms/main/items" -> json.encodeToString(Page.serializer(), Page(main))

                        "sam.dev/s/k2/info" -> json.encodeToString(
                            Info.serializer(),
                            Info(scope = "read", realm = Realm("r", "Sam's recipes"), usesLeft = 3),
                        )

                        "sam.dev/s/k2/items" -> """{"items":[]}"""

                        else -> null
                    }
                    if (body == null) {
                        respond("""{"error":"not here"}""", HttpStatusCode.NotFound)
                    } else {
                        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            },
        )
    }

    private val fresh = Item("c", "note", "just in from the phone", created = 3)

    @Test
    fun theNextRunShowsWhatWasKeptAtOnceThenRefreshesItInPlace() = runTest {
        val servers = Servers(this, listOf(fresh, photo, note))
        val home = Home(TestPlatform(prefs, cache), this, servers.engine)

        // Before any answer: the feed, its tile and the link, as they were.
        val main = home.model
        assertNotNull(main)
        assertEquals(listOf(photo, note), main.items.toList())
        assertTrue(main.loaded)
        assertEquals(listOf(photo, note), home.previews["realm:main"])
        val sam = home.shared.single()
        assertEquals(sams, sam.info)
        assertTrue(sam.canRead)

        servers.open.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(fresh, photo, note), main.items.toList())
        assertEquals(listOf(fresh, photo, note), home.previews["realm:main"])
        assertSame(sam, home.shared.single()) // it said the same: its tile wasn't touched
        // And kept for the run after.
        assertEquals(Page(listOf(fresh, photo, note)), FeedCache(cache, "realm:main").first())
        val later = Servers(this, emptyList())
        val next = Home(TestPlatform(prefs, cache), this, later.engine)
        assertEquals(listOf(fresh, photo, note), next.model?.items?.toList())
        assertEquals(listOf(fresh, photo, note), next.previews["realm:main"])
        later.open.complete(Unit)
    }

    @Test
    fun aKeptEmptyRealmIsKnownToBeEmpty() = runTest {
        val servers = Servers(this, emptyList())
        val empty = MapPrefs().apply { FeedCache(this, "realm:main").keepFirst(Page(emptyList())) }
        assertTrue(Home(TestPlatform(prefs, empty), this, servers.engine).model!!.isEmpty) // its empty state shows at once
        val unknown = Home(TestPlatform(prefs, MapPrefs()), this, servers.engine)
        assertFalse(unknown.model!!.isEmpty) // nothing kept: not known to be empty until the server says so
        servers.open.complete(Unit)
        advanceUntilIdle()
        assertTrue(unknown.model!!.isEmpty)
    }

    @Test
    fun signingOutForgetsTheRealmsButNotTheLinks() = runTest {
        val servers = Servers(this, listOf(fresh, photo, note))
        val home = Home(TestPlatform(prefs, cache), this, servers.engine)
        servers.open.complete(Unit)
        advanceUntilIdle()
        home.signOut()
        assertNull(FeedCache(cache, "realm:main").first())
        assertNull(cache.get("preview/realm:main"))
        assertNotNull(cache.get("link-info/$link")) // still connected to it
    }

    @Test
    fun removingALinkForgetsWhatItSaid() = runTest {
        val servers = Servers(this, listOf(note))
        val home = Home(TestPlatform(prefs, cache), this, servers.engine)
        servers.open.complete(Unit)
        advanceUntilIdle()
        home.disconnect(home.shared.single())
        advanceUntilIdle()
        assertNull(cache.get("link-info/$link"))
    }

    private companion object {
        val json = Json { explicitNulls = false }
    }
}
