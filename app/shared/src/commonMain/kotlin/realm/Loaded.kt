package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Fetched data as the UI sees it: not known yet, or known, maybe being fetched again. */
sealed interface Loaded<out T> {
    data object Unknown : Loaded<Nothing>

    data class Known<out T>(val value: T) : Loaded<T>
}

val <T> Loaded<T>.value: T? get() = (this as? Loaded.Known)?.value

/**
 * One piece of fetched data, kept in [cache] between runs when given. It refreshes in place, keeps what it
 * had when fetching fails, and publishes only whole values that differ from what's shown.
 */
class Fetched<T : Any>(
    private val scope: CoroutineScope,
    private val doing: String,
    private val cache: Remembered<T>? = null,
    private val failed: (String) -> Unit = {},
) {
    var state: Loaded<T> by mutableStateOf(cache?.get()?.let { Loaded.Known(it) } ?: Loaded.Unknown)
        private set

    /** A fetch is under way; apart from [state], so fetching again redraws only what shows the fetching. */
    var refreshing by mutableStateOf(false)
        private set

    private var job: Job? = null

    private var generation = 0

    /** Fetches with [fetch] instead of any fetch under way; when it fails, what was known stays and [failed] says why. */
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
                if (mine != generation) return@launch
                refreshing = false
                failed(explain(e))
                return@launch
            }
            Snapshot.withMutableSnapshot { if (mine == generation) publish(next) }
        }
    }

    /** Publishes [value], dropping any fetch under way. */
    fun set(value: T) {
        drop()
        publish(value)
    }

    /** Changes the known value, if there is one, as by [set]. */
    fun update(change: (T) -> T) {
        val known = state as? Loaded.Known ?: return
        set(change(known.value))
    }

    /** Back to nothing known, here and in the cache; drops any fetch under way. */
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

    private fun publish(value: T) {
        refreshing = false
        if (state.value == value) return
        state = Loaded.Known(value)
        cache?.set(value)
    }
}
