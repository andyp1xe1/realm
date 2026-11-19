package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * This fork's releases as GitHub last listed them, for both the app's and the server's updates: asked for at most
 * once a day unless forced, and kept between runs. [now] is in Unix seconds.
 */
class ReleaseFeed internal constructor(prefs: Prefs, private val fetch: suspend () -> Releases, now: () -> Long = Cloudflare::now) {
    private val kept = Daily(prefs, "releases", Releases.serializer(), now)
    private val asking = Mutex()

    /** The releases last listed, however long ago; null before any listing came. */
    var known by mutableStateOf(kept.get())
        private set

    /** Whether [known] was listed in the last day. */
    val fresh: Boolean get() = kept.fresh

    /** The releases: those listed in the last day unless [force]d, else GitHub's. */
    suspend fun get(force: Boolean): Releases = asking.withLock {
        known?.takeIf { !force && kept.fresh } ?: fetch().also {
            kept.set(it)
            known = it
        }
    }
}
