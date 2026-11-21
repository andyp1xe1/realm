package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Brings this device's app and the server signed in to up to date at one ask, in the order the protocol needs:
 * the Cloudflare login the server's update needs, then the app, then the server. What's left is kept between runs,
 * so it goes on once the app has restarted into its new version.
 */
class Updating internal constructor(private val home: Home, prefs: Prefs) {
    private val kept = Remembered(prefs, "updating", UpdateAsked.serializer())
    private var underway by mutableStateOf(kept.get())

    /** Whether an update was asked for and isn't done. */
    val asked: Boolean get() = underway != null

    /** What's out for this device and its server. */
    val due: Due
        get() {
            val app = home.appUpdates.available
            val speaks = home.appUpdates.availableProtocol
            val status = home.serverStatus
            val server = when (status) {
                is ServerStatus.Behind -> status.to
                is ServerStatus.AppFirst -> status.newest.takeIf { speaks != null && speaks >= status.protocol }
                else -> null
            }
            val held = when (status) {
                is ServerStatus.AppFirst -> "Server release ${status.newest} needs a newer app than this one.".takeIf { server == null }
                is ServerStatus.NewerThanApp -> "Your server is newer than this app.".takeIf { speaks == null || speaks < status.protocol }
                is ServerStatus.OtherFork -> "This app doesn't update ${status.fork ?: "realm"}."
                else -> null
            }
            return Due(app, server, held)
        }

    /** What settings shows about updates, as one value. */
    val view: UpdatesView
        get() {
            val work = home.appUpdates.state as? SelfUpdate.Work
            val check = home.serverSettings.check
            val d = due
            val signedIn = home.phase as? Phase.SignedIn
            val limited = home.releases.limited
            return when {
                work is SelfUpdate.Checking || check is Check.Checking -> UpdatesView.Checking
                work != null -> UpdatesView.App(work)
                limited != null -> UpdatesView.Limited(limited.message.orEmpty())
                check is Check.Failed -> UpdatesView.Failed(check.message)
                signedIn != null && home.serverStatus == null -> UpdatesView.Loading
                d.app != null || d.server != null -> UpdatesView.Out(home.appUpdates.version, d.app, runs(signedIn), d.server)
                d.held != null -> UpdatesView.Held(d.held)
                signedIn == null && home.appUpdates.state == SelfUpdate.NotYet -> UpdatesView.NotYet
                else -> UpdatesView.Current
            }
        }

    /** Looks for newer releases of the app and the server's worker now. */
    fun check() {
        if (home.phase is Phase.SignedIn) home.serverSettings.checkForUpdates() else home.appUpdates.check()
    }

    /** Updates whatever [due] says, in order, going on after the app restarts. */
    fun start() {
        keep(UpdateAsked(null))
        next()
    }

    internal fun next() {
        val u = underway ?: return
        val d = due
        when {
            d.server != null && home.session?.login?.account == null -> home.cloudflare.manage(then = ::next)

            d.app != null && d.app != u.app -> {
                keep(UpdateAsked(d.app))
                home.appUpdates.install()
            }

            d.server != null && d.app == null -> {
                keep(null)
                home.cloudflare.startUpdate(asked = true)
            }

            else -> keep(null)
        }
    }

    internal val readyToGoOn: Boolean get() = asked && (home.phase !is Phase.SignedIn || home.serverStatus != null)

    private fun keep(u: UpdateAsked?) {
        kept.set(u)
        underway = u
    }
}

/**
 * The app release this device can install, and the worker release its server can then be updated to; [held] says
 * why the server can't be, when it's behind but this app can't update it.
 */
data class Due(val app: Semver?, val server: Semver?, val held: String?)

/** What settings shows about updates. */
sealed interface UpdatesView {
    /** Nothing looked at yet, and no server to look at. */
    data object NotYet : UpdatesView

    /** What the server runs isn't known yet. */
    data object Loading : UpdatesView

    data object Checking : UpdatesView

    /** The app going from [appFrom] to [app], and the server from [serverFrom] to [server], each when it's out. */
    data class Out(val appFrom: AppVersion?, val app: Semver?, val serverFrom: String?, val server: Semver?) : UpdatesView

    /** Updating the app has got to [work]. */
    data class App(val work: SelfUpdate.Work) : UpdatesView

    data class Held(val reason: String) : UpdatesView

    /** GitHub is turning this network away for a while, as [reason] says; realm asks again by itself after. */
    data class Limited(val reason: String) : UpdatesView

    data class Failed(val message: String) : UpdatesView

    data object Current : UpdatesView
}

private fun runs(signedIn: Phase.SignedIn?): String? = signedIn?.facts?.state?.value?.info?.release?.let { "v$it" }
