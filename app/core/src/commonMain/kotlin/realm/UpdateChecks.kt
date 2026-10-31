// Noticing a newer worker without asking too often: the server and GitHub at most once a day, unless asked
// to, as on every sign-in. What GitHub said last is kept, so a restart doesn't ask again.
package realm

/**
 * When to look for a newer worker, and the newest release's version, with [prefs] keeping the last one GitHub
 * named and [now] the time in Unix seconds. [fetch] asks GitHub for the newest release.
 */
class UpdateChecks(private val prefs: Prefs, private val fetch: suspend () -> Release, private val now: () -> Long = Cloudflare::now) {
    private var last: Long? = null

    /** Whether to look now: when [force]d, else at most once a day. Saying yes counts as looking. */
    fun due(force: Boolean): Boolean {
        val t = now()
        if (!force && last?.let { t - it < DAY } == true) return false
        last = t
        return true
    }

    /** The API version of the newest release: the one seen within a day, unless [force]d, else GitHub's. */
    suspend fun latest(force: Boolean): Int {
        val seen = prefs.get(KEY)?.split(' ')
        if (!force && seen != null && now() - seen[0].toLong() < DAY) return seen[1].toInt()
        val r = fetch()
        prefs.set(KEY, "${now()} ${r.manifest.version} ${r.tag}")
        return r.manifest.version
    }

    /** A newer release for the worker that says [info], by its API version, if there's one to offer. */
    suspend fun newerFor(info: Info, force: Boolean): Int? = latest(force).takeIf { offersUpdate(info, it) }

    /** The tag of the newest release last seen, like v1.2.0, if one was. */
    val knownTag: String? get() = prefs.get(KEY)?.split(' ')?.getOrNull(2)

    /** Signed out: the next server is looked at straight away. */
    fun reset() {
        last = null
    }

    private companion object {
        /** When the newest release was last asked for, its API version and tag: "<unix seconds> <version> <tag>". */
        const val KEY = "release"
        const val DAY = 24 * 60 * 60L
    }
}
