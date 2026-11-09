package realm

import kotlinx.serialization.Serializable

/** When to look for a newer worker, at most once a day unless forced; [now] is in Unix seconds. */
class UpdateChecks(prefs: Prefs, private val fetch: suspend () -> Release, private val now: () -> Long = Cloudflare::now) {
    private var last: Long? = null
    private val kept = Daily(prefs, "release", SeenRelease.serializer(), now)

    /** Whether to look now: when [force]d, else when no look in the last day got an answer. */
    fun due(force: Boolean): Boolean = force || last?.let { now() - it >= DAY } != false

    /** The API version of the newest release: the one seen within a day, unless [force]d, else GitHub's. */
    suspend fun latest(force: Boolean): Int {
        if (!force && kept.fresh) kept.get()?.let { return it.version }
        val r = fetch()
        kept.set(SeenRelease(r.manifest.version, r.tag.toString()))
        return r.manifest.version
    }

    /** A newer release for the worker that says [info], by its API version, if there's one to offer. Answering counts as looking. */
    suspend fun newerFor(info: Info, force: Boolean): Int? {
        val latest = latest(force)
        last = now()
        return latest.takeIf { offersUpdate(info, it) }
    }

    /** The tag of the newest release last seen, like v1.2.0, if one was. */
    val knownTag: String? get() = kept.get()?.tag

    /** Signed out: the next server is looked at straight away. */
    fun reset() {
        last = null
    }

    private companion object {
        const val DAY = 24 * 60 * 60L
    }
}

@Serializable
private data class SeenRelease(val version: Int, val tag: String)
