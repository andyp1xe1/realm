// The server section of settings: what it shows is fetched with the app (as it starts, and on signing in) and
// kept between runs, so settings opens on it; and what its buttons ask runs through core.
package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ServerSettings internal constructor(
    private val home: Home,
    private val scope: CoroutineScope,
    private val engine: HttpClientEngine?,
) {
    /** What the section shows about the server: the last known, from the run before, then each fetch. */
    val facts = Fetched(scope, "Looking at the server", Remembered(home.platform.prefs, "server-facts", ServerFacts.serializer())) {
        home.message = it
    }

    /** Checking for a newer worker, from the button. */
    var check by mutableStateOf<Check>(Check.NotYet)
        private set

    /** What's being done to the worker's addresses, one thing at a time; null when nothing is. */
    var task by mutableStateOf<AddressTask?>(null)
        private set

    private val addresses get() = facts.state.value?.addresses

    /**
     * Asks the server what the section shows, as the app starts, on signing in and as settings opens; what's
     * known stays on show meanwhile. What was kept about another address is dropped first.
     */
    fun refresh() {
        val server = home.server ?: return
        val session = home.session
        if (facts.state.value?.origin != server.base.removeSuffix("/api")) facts.forget()
        facts.refresh { serverFacts(server, session, engine) }
    }

    internal fun signedOut() = Snapshot.withMutableSnapshot {
        facts.forget()
        check = Check.NotYet
        task = null
    }

    /** Asks for the newest release and what deploying it would do, now, whenever the last check was. */
    fun checkForUpdates() {
        val session = home.session ?: return
        val account = addresses?.account ?: return
        check = Check.Checking
        scope.launch {
            check = try {
                Check.Done(CloudflareApi(session, engine).use { it.checkUpdate(account) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Checking for updates", e)
                Check.Failed(explain(e))
            }
        }
    }

    /** Opens or closes the form for a domain of the account's; a failure shown there goes with it. */
    fun adding(open: Boolean) {
        if (addresses != null) task = AddressTask.Adding.takeIf { open }
    }

    /** Puts the worker on [choice]'s hostname, asking first if DNS records are in the way. */
    fun addDomain(choice: DomainChoice) = attach(choice, null)

    /** Answers [AddressTask.Conflict]: deletes the records shown and attaches, or leaves them. */
    fun replace(agree: Boolean) {
        val asked = task as? AddressTask.Conflict ?: return
        if (agree) attach(asked.conflict.choice, asked.conflict) else task = null
    }

    private fun attach(choice: DomainChoice, agreed: DomainOutcome.Conflict?) = work(AddressTask.Attaching(choice.hostname)) { cf, a ->
        when (val out = cf.attachDomain(a.account, choice, agreed)) {
            is DomainOutcome.Conflict -> AddressTask.Conflict(out)
            is DomainOutcome.Attached -> null
        }
    }

    /**
     * Asks before taking the worker off [origin]: share links made with it stop working. Off the address in
     * use, the app moves to another one first.
     */
    fun remove(origin: String) {
        val f = facts.state.value ?: return
        val others = (f.addresses ?: return).origins - origin
        val moveTo = if (origin == f.origin) others.firstOrNull() ?: return else null
        task = AddressTask.Removing(origin, confirmed = false, moveTo)
    }

    /** Answers [AddressTask.Removing]: takes the worker off the address, moving the app first if it's the one in use, or not. */
    fun confirmRemove(agree: Boolean) {
        val asked = task as? AddressTask.Removing ?: return
        if (!agree) {
            task = null
            return
        }
        val moveTo = asked.moveTo
        if (moveTo == null) {
            work(asked.copy(confirmed = true)) { cf, a ->
                cf.takeOff(a, asked.origin)
                null
            }
        } else {
            switch(moveTo, asked.copy(confirmed = true)) { cf, a -> cf.takeOff(a, asked.origin) }
        }
    }

    /** Turns the worker's workers.dev address back on. */
    fun turnOnWorkersDev() {
        val origin = addresses?.workersDev ?: return
        work(AddressTask.Attaching(origin.removePrefix("https://"))) { cf, a ->
            cf.setWorkersDev(a.account, on = true)
            null
        }
    }

    /** Makes [origin] the address the app talks to, once the worker answers its owner there; the login stays. */
    fun use(origin: String) = switch(origin, AddressTask.Switching(origin)) { _, _ -> }

    /**
     * Shows [task] while signing in at [origin], the same worker at another address, and doing [then] with the
     * login; then the app and this section move there in one go. Nothing changes if any of it fails.
     */
    private fun switch(origin: String, task: AddressTask, then: suspend (CloudflareApi, Addresses) -> Unit) {
        val session = home.session ?: return
        val a = addresses ?: return
        this.task = task
        scope.launch {
            try {
                val s = signInWithCloudflare(origin, session.login, home.platform.name, home::sessionFor, engine)
                val next = try {
                    CloudflareApi(session, engine).use { then(it, a) }
                    serverFacts(s.api, s.session, engine)
                } catch (e: Exception) {
                    s.api.close() // nothing else will hold it
                    throw e
                }
                // The app and this section move to the new address in the same frame.
                Snapshot.withMutableSnapshot {
                    home.switchedTo(s)
                    facts.set(next)
                    check = Check.NotYet
                    this@ServerSettings.task = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Moving the app to $origin", e)
                this@ServerSettings.task = AddressTask.Failed(explain(e))
            }
        }
    }

    /**
     * Shows [task] while [block] changes the worker's addresses, then the addresses as they are now, with what
     * [block] says is left to ask, or why it failed.
     */
    private fun work(task: AddressTask, block: suspend (CloudflareApi, Addresses) -> AddressTask?) {
        val session = home.session ?: return
        val a = addresses ?: return
        this.task = task
        scope.launch {
            try {
                CloudflareApi(session, engine).use { cf ->
                    val next = block(cf, a)
                    val now = cf.addresses(a.account)
                    Snapshot.withMutableSnapshot {
                        facts.update { it.copy(addresses = now) }
                        this@ServerSettings.task = next
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Changing the server's addresses", e)
                this@ServerSettings.task = AddressTask.Failed(explain(e))
            }
        }
    }
}

/** Checking for a newer worker, from the button. */
sealed interface Check {
    data object NotYet : Check

    data object Checking : Check

    data class Done(val result: UpdateCheck) : Check

    data class Failed(val message: String) : Check
}

/** What's being done to the worker's addresses, one thing at a time. */
sealed interface AddressTask {
    /** The form for a domain of the account's is open. */
    data object Adding : AddressTask

    data class Attaching(val hostname: String) : AddressTask

    /** DNS records stand in the way of [conflict]'s hostname: shown, and deleted only if the user agrees. */
    data class Conflict(val conflict: DomainOutcome.Conflict) : AddressTask

    /**
     * Taking the worker off [origin], a custom domain or workers.dev: asked first, then [confirmed]. [moveTo]
     * is where the app goes first when [origin] is the address it uses.
     */
    data class Removing(val origin: String, val confirmed: Boolean, val moveTo: String?) : AddressTask

    /** Making [origin] the address the app uses, once it answers there. */
    data class Switching(val origin: String) : AddressTask

    data class Failed(val message: String) : AddressTask
}
