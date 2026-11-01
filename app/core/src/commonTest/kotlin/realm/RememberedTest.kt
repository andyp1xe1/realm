package realm

import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RememberedTest {
    private val prefs = MapPrefs()
    private val realms = Remembered(prefs, "realms", ListSerializer(Realm.serializer()))

    @Test
    fun givesBackWhatWasKeptAcrossRuns() {
        realms.set(listOf(Realm("main", "Main", count = 3)))
        // A new run: a new Remembered over the same prefs.
        assertEquals(listOf(Realm("main", "Main", count = 3)), Remembered(prefs, "realms", ListSerializer(Realm.serializer())).get())
    }

    @Test
    fun nothingKeptIsNull() = assertNull(realms.get())

    @Test
    fun forgetting() {
        realms.set(listOf(Realm("main", "Main")))
        realms.set(null)
        assertNull(realms.get())
        assertNull(prefs.get("realms"))
    }

    @Test
    fun whatNoLongerReadsIsTreatedAsAbsent() {
        prefs.set("realms", "{not json")
        assertNull(realms.get())
        prefs.set("realms", """[{"name":"no id"}]""") // an older shape, missing a field
        assertNull(realms.get())
        prefs.set("realms", """{"realms":[]}""") // another type altogether
        assertNull(realms.get())
    }

    @Test
    fun fieldsAddedSinceAreIgnored() {
        prefs.set("realms", """[{"id":"main","name":"Main","colour":"blue"}]""")
        assertEquals(listOf(Realm("main", "Main")), realms.get())
    }

    @Test
    fun serverFactsRoundTrip() {
        val facts = Remembered(prefs, "server-facts", ServerFacts.serializer())
        val a = Addresses(Account("a1", "Jo"), "https://realm.jo.workers.dev", true, emptyList(), listOf(Zone("z1", "jo.dev")))
        val f = ServerFacts("https://realm.jo.workers.dev", Info(version = 3, scope = "owner"), a)
        facts.set(f)
        assertEquals(f, facts.get())
    }
}
