package realm

/** When to look for a newer worker, at most once a day unless forced; [now] is in Unix seconds. */
class UpdateChecks(private val prefs: Prefs, private val fetch: suspend () -> Release, private val now: () -> Long = Cloudflare::now) {
    private var last: Long? = null

    /** Whether to look now: when [force]d, else when no look in the last day got an answer. */
    fun due(force: Boolean): Boolean = force || last?.let { now() - it >= DAY } != false

    /** The API version of the newest release: the one seen within a day, unless [force]d, else GitHub's. */
    suspend fun latest(force: Boolean): Int {
        val seen = prefs.get(KEY)?.split(' ')
        if (!force && seen != null && now() - seen[0].toLong() < DAY) return seen[1].toInt()
        val r = fetch()
        prefs.set(KEY, "${now()} ${r.manifest.version} ${r.tag}")
        return r.manifest.version
    }

    /** A newer release for the worker that says [info], by its API version, if there's one to offer. Answering counts as looking. */
    suspend fun newerFor(info: Info, force: Boolean): Int? {
        val latest = latest(force)
        last = now()
        return latest.takeIf { offersUpdate(info, it) }
    }

    /** The tag of the newest release last seen, like v1.2.0, if one was. */
    val knownTag: String? get() = prefs.get(KEY)?.split(' ')?.getOrNull(2)

    /** Signed out: the next server is looked at straight away. */
    fun reset() {
        last = null
    }

    private companion object {
        const val KEY = "release"
        const val DAY = 24 * 60 * 60L
    }
}
