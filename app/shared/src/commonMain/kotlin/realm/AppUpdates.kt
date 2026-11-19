package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps this copy of the app on its fork's newest stable release, when it can replace itself, from the [releases]
 * listed. Looks once a day without a word; installs only when asked. [now] is in Unix seconds.
 */
class AppUpdates internal constructor(
    prefs: Prefs,
    private val updater: Updater?,
    private val releases: suspend (force: Boolean) -> Releases,
    private val scope: CoroutineScope,
    private val now: () -> Long = Cloudflare::now,
) {
    private val kept = Daily(prefs, "app-update", AppLook.serializer(), now)
    private var job: Job? = null

    /** This copy's version; null when it can't update itself. */
    val version: AppVersion? get() = updater?.version

    private var looked by mutableStateOf(kept.get())
    private var work by mutableStateOf<SelfUpdate.Work?>(null)

    /** The release newer than this copy that the last look found, here or in an earlier run. */
    val available: Semver? get() = looked?.newer?.takeIf { version?.olderThan(it) == true }

    /** What's being done, else what the last look found. */
    val state: SelfUpdate
        get() = work ?: available?.let(SelfUpdate::Available) ?: if (looked == null) SelfUpdate.NotYet else SelfUpdate.UpToDate

    /** Whether there's an update to install or restart into, whatever came of the last try. */
    val waiting: Boolean get() = available != null || work is SelfUpdate.Ready

    private val busy get() = job?.isActive == true

    /** Looks for a newer release, without showing it's looking, if there was no answer in the last day. */
    fun refresh() {
        val u = updater ?: return
        if (busy || work is SelfUpdate.Ready || kept.fresh) return
        job = scope.launch {
            try {
                look(u, force = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Looking for a newer app", e)
            }
        }
    }

    /** Looks for a newer release now. */
    fun check() = work(installs = false, SelfUpdate.Checking) { u ->
        look(u, force = true)
        work = null
    }

    /** Downloads the newest release and puts it in place of the app, asking first where the system does. */
    fun install() = work(installs = true, available?.let { SelfUpdate.Downloading(it, 0f) } ?: SelfUpdate.Checking) { u ->
        val update = look(u, force = false)
        if (update == null) {
            work = null
            return@work
        }
        var shown = -1
        work = SelfUpdate.Downloading(update.tag, 0f)
        val placed = u.install(
            update,
            progress = { p ->
                val percent = (p * 100).toInt().coerceIn(0, 100)
                if (percent != shown) {
                    shown = percent
                    work = SelfUpdate.Downloading(update.tag, percent / 100f)
                }
            },
            installing = { work = SelfUpdate.Installing(update.tag) },
        )
        work = if (placed) SelfUpdate.Ready(update.tag) else null
    }

    /** Runs the update put in place. */
    fun restart() {
        if (work is SelfUpdate.Ready) updater?.restart()
    }

    private suspend fun look(u: Updater, force: Boolean): AppUpdate? {
        val update = u.newer(releases(force))
        val l = AppLook(update?.tag)
        kept.set(l)
        looked = l
        return update
    }

    private fun work(installs: Boolean, first: SelfUpdate.Work, block: suspend (Updater) -> Unit) {
        val u = updater ?: return
        if (work is SelfUpdate.Downloading || work is SelfUpdate.Installing || work is SelfUpdate.Ready) return
        work = first
        job?.cancel()
        job = scope.launch {
            try {
                block(u)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Updating the app", e)
                work = SelfUpdate.Failed(explain(e), retriesInstall = installs)
            }
        }
    }
}

/** Where updating the app stands. */
sealed interface SelfUpdate {
    /** Never looked, in this run or an earlier one. */
    data object NotYet : SelfUpdate

    data object UpToDate : SelfUpdate

    data class Available(val tag: Semver) : SelfUpdate

    /** Something being done about it, or what came of that. */
    sealed interface Work : SelfUpdate

    data object Checking : Work

    /** [progress] goes from 0 to 1. */
    data class Downloading(val tag: Semver, val progress: Float) : Work

    /** Downloaded and checked; being put in place, or waiting on the system to ask. */
    data class Installing(val tag: Semver) : Work

    /** In place; a restart runs it. */
    data class Ready(val tag: Semver) : Work

    /** [retriesInstall]: trying again installs, rather than looks. */
    data class Failed(val message: String, val retriesInstall: Boolean) : Work
}
