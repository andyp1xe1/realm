// The server section of settings: fetches what it shows and runs what its buttons ask, all through core,
// publishing each result as one whole ServerPanel.
package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ServerSettings internal constructor(
    private val home: Home,
    private val scope: CoroutineScope,
    private val engine: HttpClientEngine?,
) {
    /** What the section shows; null until it's fetched, and for share links only. */
    var panel by mutableStateOf<ServerPanel?>(null)
        private set

    private var job: Job? = null

    /** Fetches what the section shows, as settings opens. It stays blank until then. */
    fun load() {
        val server = home.server ?: return run { panel = null }
        val session = home.session
        job?.cancel()
        job = scope.launch {
            val next = try {
                fetch(server, session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Looking at the server", e)
                home.message = explain(e)
                return@launch
            }
            if (home.server === server) panel = next
        }
    }

    private suspend fun fetch(server: Api, session: CloudflareSession?): ServerPanel {
        val info = server.info()
        val managed = session?.login?.account?.let { id ->
            CloudflareApi(session, engine).use { cf -> Managed(session, cf.addresses(cf.account(id)), Check.NotYet, null) }
        }
        return ServerPanel(server.base.removeSuffix("/api"), info, home.cloudflare.knownRelease(), managed)
    }

    internal fun signedOut() {
        job?.cancel()
        panel = null
    }

    /** Asks for the newest release and what deploying it would do, now, whenever the last check was. */
    fun checkForUpdates() {
        val m = panel?.managed ?: return
        edit { it.copy(check = Check.Checking) }
        scope.launch {
            val check = try {
                Check.Done(CloudflareApi(m.session, engine).use { it.checkUpdate(m.addresses.account) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Checking for updates", e)
                Check.Failed(explain(e))
            }
            edit { it.copy(check = check) }
        }
    }

    /** Opens or closes the form for a domain of the account's; a failure shown there goes with it. */
    fun adding(open: Boolean) = edit { it.copy(task = AddressTask.Adding.takeIf { open }) }

    /** Puts the worker on [choice]'s hostname, asking first if DNS records are in the way. */
    fun addDomain(choice: DomainChoice) = attach(choice, null)

    /** Answers [AddressTask.Conflict]: deletes the records shown and attaches, or leaves them. */
    fun replace(agree: Boolean) {
        val asked = panel?.managed?.task as? AddressTask.Conflict ?: return
        if (agree) attach(asked.conflict.choice, asked.conflict) else edit { it.copy(task = null) }
    }

    private fun attach(choice: DomainChoice, agreed: DomainOutcome.Conflict?) = work(AddressTask.Attaching(choice.hostname)) { cf, m ->
        when (val out = cf.attachDomain(m.addresses.account, choice, agreed)) {
            is DomainOutcome.Conflict -> AddressTask.Conflict(out)
            is DomainOutcome.Attached -> null
        }
    }

    /**
     * Asks before taking the worker off [origin]: share links made with it stop working. Off the address in
     * use, the app moves to another one first.
     */
    fun remove(origin: String) {
        val p = panel ?: return
        val others = p.managed?.addresses?.origins.orEmpty() - origin
        val moveTo = if (origin == p.origin) others.firstOrNull() ?: return else null
        edit { it.copy(task = AddressTask.Removing(origin, confirmed = false, moveTo)) }
    }

    /** Answers [AddressTask.Removing]: takes the worker off the address, moving the app first if it's the one in use, or not. */
    fun confirmRemove(agree: Boolean) {
        val asked = panel?.managed?.task as? AddressTask.Removing ?: return
        if (!agree) return edit { it.copy(task = null) }
        val moveTo = asked.moveTo
        if (moveTo == null) {
            work(asked.copy(confirmed = true)) { cf, m ->
                cf.takeOff(m.addresses, asked.origin)
                null
            }
        } else {
            switch(moveTo, asked.copy(confirmed = true)) { cf, m -> cf.takeOff(m.addresses, asked.origin) }
        }
    }

    /** Turns the worker's workers.dev address back on. */
    fun turnOnWorkersDev() {
        val origin = panel?.managed?.addresses?.workersDev ?: return
        work(AddressTask.Attaching(origin.removePrefix("https://"))) { cf, m ->
            cf.setWorkersDev(m.addresses.account, on = true)
            null
        }
    }

    /** Makes [origin] the address the app talks to, once the worker answers its owner there; the login stays. */
    fun use(origin: String) = switch(origin, AddressTask.Switching(origin)) { _, _ -> }

    /**
     * Shows [task] while signing in at [origin], the same worker at another address, and doing [then] with the
     * login; then the app and this section move there in one go. Nothing changes if any of it fails.
     */
    private fun switch(origin: String, task: AddressTask, then: suspend (CloudflareApi, Managed) -> Unit) {
        val m = panel?.managed ?: return
        edit { it.copy(task = task) }
        scope.launch {
            try {
                val s = signInWithCloudflare(origin, m.session.login, home.platform.name, home::sessionFor, engine)
                val next = try {
                    CloudflareApi(m.session, engine).use { then(it, m) }
                    fetch(s.api, s.session)
                } catch (e: Exception) {
                    s.api.close() // nothing else will hold it
                    throw e
                }
                // The app and this section move to the new address in the same frame.
                Snapshot.withMutableSnapshot {
                    home.switchedTo(s)
                    panel = next
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Moving the app to $origin", e)
                edit { it.copy(task = AddressTask.Failed(explain(e))) }
            }
        }
    }

    /**
     * Shows [task] while [block] changes the worker's addresses, then the addresses as they are now, with what
     * [block] says is left to ask, or why it failed.
     */
    private fun work(task: AddressTask, block: suspend (CloudflareApi, Managed) -> AddressTask?) {
        val m = panel?.managed ?: return
        edit { it.copy(task = task) }
        scope.launch {
            try {
                CloudflareApi(m.session, engine).use { cf ->
                    val next = block(cf, m)
                    val addresses = cf.addresses(m.addresses.account)
                    edit { it.copy(addresses = addresses, task = next) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Changing the server's addresses", e)
                edit { it.copy(task = AddressTask.Failed(explain(e))) }
            }
        }
    }

    /** Replaces the managed part of the panel as it is now, in one write. */
    private fun edit(change: (Managed) -> Managed) {
        val p = panel ?: return
        panel = p.copy(managed = p.managed?.let(change))
    }
}
