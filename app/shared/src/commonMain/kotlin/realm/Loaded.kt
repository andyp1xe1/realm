// Data the app fetches and shows: the one supported way to hold it. Fetched starts from the last value kept,
// refreshes in place, keeps what it had when asking fails, and publishes whole values, and only ones that differ
// from what's shown: a refresh that finds the same changes nothing, so nothing redraws. ui/Loading.kt draws it.
package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Fetched data as the UI sees it: never known yet, or known, maybe being asked for again. */
sealed interface Loaded<out T> {
    /** Nothing known yet: the UI keeps the room the data takes, blank, with a skeleton only if it is slow to come. */
    data object Unknown : Loaded<Nothing>

    /** [value] as last fetched or kept, shown while it's asked for again ([Fetched.refreshing]). */
    data class Known<out T>(val value: T) : Loaded<T>
}

/** The value, if it's known. */
val <T> Loaded<T>.value: T? get() = (this as? Loaded.Known)?.value

/**
 * One piece of fetched data, kept in [cache] between runs when given. [doing] says what fetching is, like
 * "Looking at the server", for the log; [failed] gets what to tell the user when it goes wrong.
 */
class Fetched<T : Any>(
    private val scope: CoroutineScope,
    private val doing: String,
    private val cache: Remembered<T>? = null,
    private val failed: (String) -> Unit = {},
) {
    /** The last value kept, from the start; then each one fetched or [set], whole. */
    var state: Loaded<T> by mutableStateOf(cache?.get()?.let { Loaded.Known(it) } ?: Loaded.Unknown)
        private set

    /**
     * A fetch is under way. Apart from [state], so that asking again changes nothing a reader of the value sees:
     * only what shows the asking, like a spinner, reads this.
     */
    var refreshing by mutableStateOf(false)
        private set

    private var job: Job? = null

    /** Each fetch and forget gets the next number; a fetch publishes only if it's still the latest. */
    private var generation = 0

    /**
     * Asks again with [fetch], instead of whatever asking is under way, keeping what's known on show meanwhile.
     * When it fails, what was known stays, and [failed] says why.
     */
    fun refresh(fetch: suspend () -> T) {
        job?.cancel()
        val mine = ++generation
        refreshing = true
        job = scope.launch {
            val next = try {
                fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(doing, e)
                if (mine != generation) return@launch // given up on meanwhile
                refreshing = false
                failed(explain(e))
                return@launch
            }
            Snapshot.withMutableSnapshot { if (mine == generation) publish(next) }
        }
    }

    /** A whole new value, from a change made here: newer than what a fetch under way began with, so that's dropped. */
    fun set(value: T) {
        drop()
        publish(value)
    }

    /** Changes the known value, if there is one, in one write; a fetch under way is dropped, as by [set]. */
    fun update(change: (T) -> T) {
        val known = state as? Loaded.Known ?: return
        set(change(known.value))
    }

    /** Back to nothing known, here and in the cache, as on signing out; a fetch under way is dropped. */
    fun forget() {
        drop()
        refreshing = false
        state = Loaded.Unknown
        cache?.set(null)
    }

    private fun drop() {
        job?.cancel()
        generation++
    }

    /** [value], unless it's the one shown already: then nothing changes, and nothing redraws. */
    private fun publish(value: T) {
        refreshing = false
        if (state.value == value) return
        state = Loaded.Known(value)
        cache?.set(value)
    }
}
