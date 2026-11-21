package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * This fork's releases as GitHub last listed them, for both the app's and the server's updates: asked for at most
 * once a day unless forced, and kept between runs; [limitedUntil] hears when GitHub turns this network away. [now] is
 * in Unix seconds.
 */
class ReleaseFeed internal constructor(
    prefs: Prefs,
    private val fetch: suspend () -> Releases,
    private val now: () -> Long = Cloudflare::now,
    private val limitedUntil: (GitHubBusy) -> Unit = {},
) {
    private val kept = Daily(prefs, "releases", Releases.serializer(), now)
    private val asking = Mutex()

    /** The releases last listed, however long ago; null before any listing came. */
    var known by mutableStateOf(kept.get())
        private set

    /** Whether [known] was listed in the last day. */
    val fresh: Boolean get() = kept.fresh

    /** Why GitHub isn't asked now: it's turning this network away until then; null when it isn't. */
    var limited by mutableStateOf<GitHubBusy?>(null)
        private set

    /** The releases: those listed in the last day unless [force]d, else GitHub's, unless it's [limited] us. */
    suspend fun get(force: Boolean): Releases = asking.withLock {
        known?.takeIf { !force && kept.fresh }?.let { return@withLock it }
        limited?.takeIf { it.until > now() }?.let { throw it }
        try {
            fetch().also {
                kept.set(it)
                known = it
                limited = null
            }
        } catch (e: GitHubBusy) {
            limited = e
            limitedUntil(e)
            throw e
        }
    }
}
