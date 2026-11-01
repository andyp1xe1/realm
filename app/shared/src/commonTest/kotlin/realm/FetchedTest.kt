package realm

import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FetchedTest {
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
        assertEquals(Loaded.Known(main), f.state) // shown meanwhile, not a skeleton
        assertTrue(f.refreshing)
        answer.complete(both)
        runCurrent()
        assertEquals(Loaded.Known(both), f.state)
        assertFalse(f.refreshing)
        assertEquals(both, cache.get()) // and kept for the run after
    }

    /** The state objects reading [Fetched.state] reads, as a screen showing it would. */
    private fun readBy(f: Fetched<*>): Set<Any> = mutableSetOf<Any>().also { reads -> Snapshot.observe({ reads += it }) { f.state } }

    /** Runs [change] and gives the state objects it changed, as a screen would be told of them. */
    private fun TestScope.changedBy(change: () -> Unit): Set<Any> {
        val changed = mutableSetOf<Any>()
        val h = Snapshot.registerApplyObserver { set, _ -> changed.addAll(set) }
        change()
        runCurrent()
        Snapshot.sendApplyNotifications()
        h.dispose()
        return changed
    }

    @Test
    fun aRefreshThatFindsTheSameChangesNothingAScreenShowingItReads() = runTest {
        cache.set(main)
        val f = fetched()
        val shown = readBy(f)
        assertTrue(changedBy { f.refresh { main } }.none { it in shown }) // asked, and found the same: no redraw
        assertEquals(Loaded.Known(main), f.state)
        assertFalse(f.refreshing)
        assertTrue(changedBy { f.refresh { both } }.any { it in shown }) // found something new: it's shown
        assertEquals(Loaded.Known(both), f.state)
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
