package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * Keeps this copy of the app on its fork's newest stable release, when it can replace itself. Looks once a day
 * without a word; installs only when asked. [now] is in Unix seconds.
 */
class AppUpdates internal constructor(
    prefs: Prefs,
    private val updater: Updater?,
    private val scope: CoroutineScope,
    private val now: () -> Long = Cloudflare::now,
) {
    private val seen = Daily(prefs, "app-release", String.serializer().nullable, now)
    private var job: Job? = null

    /** This copy's version; null when it can't update itself. */
    val version: AppVersion? get() = updater?.version

    var state by mutableStateOf(kept())
        private set

    /** Whether there's an update to install or restart into. */
    val waiting: Boolean get() = state is SelfUpdate.Available || state is SelfUpdate.Ready

    private val busy get() = job?.isActive == true

    private fun kept(): SelfUpdate {
        val tag = seen.get()?.let(Semver::parse) ?: return SelfUpdate.NotYet
        return if (updater?.version?.olderThan(tag) == true) SelfUpdate.Available(tag) else SelfUpdate.NotYet
    }

    /** Looks for a newer release, without showing it's looking, if there was no answer in the last day. */
    fun refresh() {
        val u = updater ?: return
        if (busy || state is SelfUpdate.Ready || seen.fresh) return
        job = scope.launch {
            try {
                state = found(u.newer())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Looking for a newer app", e)
            }
        }
    }

    /** Looks for a newer release now. */
    fun check() = work(installs = false, SelfUpdate.Checking) { u -> state = found(u.newer()) }

    /** Downloads the newest release and puts it in place of the app, asking first where the system does. */
    fun install() = work(
        installs = true,
        (state as? SelfUpdate.Available)?.let { SelfUpdate.Downloading(it.tag, 0f) } ?: SelfUpdate.Checking,
    ) { u ->
        val update = u.newer()
        val looked = found(update)
        if (update == null) {
            state = looked
            return@work
        }
        var shown = -1
        state = SelfUpdate.Downloading(update.tag, 0f)
        val placed = u.install(
            update,
            progress = { p ->
                val percent = (p * 100).toInt().coerceIn(0, 100)
                if (percent != shown) {
                    shown = percent
                    state = SelfUpdate.Downloading(update.tag, percent / 100f)
                }
            },
            installing = { state = SelfUpdate.Installing(update.tag) },
        )
        state = if (placed) SelfUpdate.Ready(update.tag) else SelfUpdate.Available(update.tag)
    }

    /** Runs the update put in place. */
    fun restart() {
        if (state is SelfUpdate.Ready) updater?.restart()
    }

    private fun found(update: AppUpdate?): SelfUpdate {
        seen.set(update?.tag?.toString())
        return update?.let { SelfUpdate.Available(it.tag) } ?: SelfUpdate.UpToDate
    }

    private fun work(installs: Boolean, first: SelfUpdate, block: suspend (Updater) -> Unit) {
        val u = updater ?: return
        if (state is SelfUpdate.Downloading || state is SelfUpdate.Installing || state is SelfUpdate.Ready) return
        state = first
        job?.cancel()
        job = scope.launch {
            try {
                block(u)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Updating the app", e)
                state = SelfUpdate.Failed(explain(e), retriesInstall = installs)
            }
        }
    }
}

/** Where updating the app has got to. */
sealed interface SelfUpdate {
    /** Not asked this run, and nothing newer known. */
    data object NotYet : SelfUpdate

    data object Checking : SelfUpdate

    data object UpToDate : SelfUpdate

    data class Available(val tag: Semver) : SelfUpdate

    /** [progress] goes from 0 to 1. */
    data class Downloading(val tag: Semver, val progress: Float) : SelfUpdate

    /** Downloaded and checked; being put in place, or waiting on the system to ask. */
    data class Installing(val tag: Semver) : SelfUpdate

    /** In place; a restart runs it. */
    data class Ready(val tag: Semver) : SelfUpdate

    /** [retriesInstall]: trying again installs, rather than looks. */
    data class Failed(val message: String, val retriesInstall: Boolean) : SelfUpdate
}
