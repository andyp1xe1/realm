package realm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FetchedTest {
    private class MapPrefs : Prefs {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun set(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private val prefs = MapPrefs()
    private val cache = Remembered(prefs, "realms", ListSerializer(Realm.serializer()))
    private val main = listOf(Realm("main", "Main"))
    private val both = listOf(Realm("main", "Main"), Realm("trip", "Lisbon trip"))
    private val said = mutableListOf<String>()

    private fun TestScope.fetched() = Fetched(this, "Looking at the realms", cache) { said += it }

    @Test
    fun aColdStartIsUnknownUntilFetched() = runTest {
        val f = fetched()
        assertEquals(Loaded.Unknown, f.state)
        val answer = CompletableDeferred<List<Realm>>()
        f.refresh { answer.await() }
        runCurrent()
        assertEquals(Loaded.Unknown, f.state) // still asking: nothing to show yet
        answer.complete(main)
        runCurrent()
        assertEquals(Loaded.Known(main), f.state)
    }

    @Test
    fun theNextRunShowsTheLastValueFirstAndRefreshesInPlace() = runTest {
        fetched().apply { refresh { main } }
        runCurrent()

        // The next run: a new holder over the same prefs knows the realms before asking.
        val f = fetched()
        assertEquals(Loaded.Known(main), f.state)
        val answer = CompletableDeferred<List<Realm>>()
        f.refresh { answer.await() }
        runCurrent()
        assertEquals(Loaded.Known(main, refreshing = true), f.state) // shown meanwhile, not a skeleton
        answer.complete(both)
        runCurrent()
        assertEquals(Loaded.Known(both), f.state)
        assertEquals(both, cache.get()) // and kept for the run after
    }

    @Test
    fun aFailureKeepsTheLastValueAndSaysWhy() = runTest {
        cache.set(main)
        val f = fetched()
        f.refresh { throw ApiException("can't reach the server") }
        runCurrent()
        assertEquals(Loaded.Known(main), f.state)
        assertEquals(main, cache.get())
        assertEquals(1, said.size)
    }

    @Test
    fun aFailureWithNothingKnownStaysUnknown() = runTest {
        val f = fetched()
        f.refresh { throw ApiException("can't reach the server") }
        runCurrent()
        assertEquals(Loaded.Unknown, f.state)
        assertEquals(1, said.size)
    }

    @Test
    fun aCacheThatNoLongerReadsIsIgnored() = runTest {
        prefs.set("realms", """{"realms":"from an older app"}""")
        assertEquals(Loaded.Unknown, fetched().state)
    }

    @Test
    fun aChangeMadeHereWinsOverAFetchAlreadyUnderWay() = runTest {
        val f = fetched()
        val answer = CompletableDeferred<List<Realm>>()
        f.refresh { answer.await() }
        runCurrent()
        f.set(both)
        answer.complete(main) // began before the change: out of date
        runCurrent()
        assertEquals(Loaded.Known(both), f.state)
        f.update { it.take(1) }
        assertEquals(Loaded.Known(main), f.state)
        assertEquals(main, cache.get())
    }

    @Test
    fun forgettingDropsTheValueTheCacheAndAFetchUnderWay() = runTest {
        cache.set(main)
        val f = fetched()
        val answer = CompletableDeferred<List<Realm>>()
        f.refresh { answer.await() }
        f.forget()
        answer.complete(both)
        runCurrent()
        assertEquals(Loaded.Unknown, f.state)
        assertNull(cache.get())
    }
}
