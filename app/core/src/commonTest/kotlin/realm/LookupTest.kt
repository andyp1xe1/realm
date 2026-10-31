package realm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LookupTest {
    private val realms =
        listOf(Realm("r1", "Main"), Realm("r2", "Lisbon trip"), Realm("r3", "Lists"), Realm("r4", "Trip"), Realm("r5", "Trip 2"))

    @Test
    fun findsARealmByIdNameOrTheStartOfOne() {
        assertEquals("r3", findRealm(realms, "r3").id)
        assertEquals("r1", findRealm(realms, "MAIN").id)
        assertEquals("r2", findRealm(realms, "lisb").id)
        assertEquals("r4", findRealm(realms, "trip").id) // a whole name beats the start of a longer one
    }

    @Test
    fun saysWhichRealmsAStartCouldBe() {
        val e = assertFailsWith<ApiException> { findRealm(realms, "li") }
        assertTrue("Lisbon trip" in e.message!! && "Lists" in e.message!!, e.message)
        assertFailsWith<ApiException> { findRealm(realms, "nope") }
    }

    @Test
    fun theCurrentRealmIsTheChosenOneElseTheFirst() {
        assertEquals("r1", currentRealm(realms, null)?.id)
        assertEquals("r4", currentRealm(realms, "r4")?.id)
        assertNull(currentRealm(realms, "gone"))
    }

    @Test
    fun lastIsTheNewestItemAndAnythingElseAnId() = runTest {
        val server = FakeServer { path ->
            when (path) {
                "/api/items" -> json("""{"items":[{"id":"new","kind":"note","text":"hi","created":2}]}""")
                "/api/items/old" -> json("""{"id":"old","kind":"note","text":"yo","created":1}""")
                else -> notFound()
            }
        }
        val api = Api("https://realm.jo.dev/api", Bearer { "t" }, "test", server.engine)
        assertEquals("new", api.resolve("last").id)
        assertEquals("1", server.requests.last().url.parameters["limit"])
        assertEquals("old", api.resolve("old").id)
    }

    @Test
    fun lastInAnEmptyRealmIsAnError() = runTest {
        val api = Api("https://realm.jo.dev/api", Bearer { "t" }, "test", FakeServer { json("""{"items":[]}""") }.engine)
        assertFailsWith<ApiException> { api.resolve("last") }
    }

    @Test
    fun revokesByTokenOrLink() {
        assertEquals("tok123", linkToken("tok123"))
        assertEquals("tok123", linkToken("https://realm.jo.dev/s/tok123/"))
    }
}
