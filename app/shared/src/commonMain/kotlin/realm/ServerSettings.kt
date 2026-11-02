package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ServerSettings internal constructor(private val home: Home, private val engine: HttpClientEngine?) {
    /** What the section shows about the server signed in to, kept between runs; null when signed in to none. */
    val facts: Fetched<ServerFacts>? get() = (home.phase as? Phase.SignedIn)?.facts

    var check by mutableStateOf<Check>(Check.NotYet)
        private set

    /** What's being done to the worker's addresses; null when nothing is. */
    var task by mutableStateOf<AddressTask?>(null)
        private set

    private val addresses get() = facts?.state?.value?.addresses

    internal fun factsAt(server: Api, scope: CoroutineScope) = Fetched(
        scope,
        "Looking at the server",
        Remembered(home.platform.prefs, "server-facts/${server.base.removeSuffix("/api")}", ServerFacts.serializer()),
    ) { home.message = it }

    /** Asks the server what the section shows. */
    fun refresh() {
        val signedIn = home.phase as? Phase.SignedIn ?: return
        signedIn.facts.refresh { serverFacts(signedIn.server, signedIn.session, engine) }
    }

    internal fun signedOut() {
        check = Check.NotYet
        task = null
    }

    /** Asks for the newest release and what deploying it would do. */
    fun checkForUpdates() {
        val p = home.phase as? Phase.SignedIn ?: return
        val session = p.session ?: return
        val account = addresses?.account ?: return
        check = Check.Checking
        p.scope.launch {
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

    /** Opens or closes the form for a domain of the account's. */
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

    /** Asks before taking the worker off [origin]: share links made with it stop working. */
    fun remove(origin: String) {
        val f = facts?.state?.value ?: return
        val others = (f.addresses ?: return).origins - origin
        val moveTo = if (origin == f.origin) others.firstOrNull() ?: return else null
        task = AddressTask.Removing(origin, confirmed = false, moveTo)
    }

    /** Answers [AddressTask.Removing]; the app moves first if it's the address in use. */
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

    fun turnOnWorkersDev() {
        val origin = addresses?.workersDev ?: return
        work(AddressTask.Attaching(origin.removePrefix("https://"))) { cf, a ->
            cf.setWorkersDev(a.account, on = true)
            null
        }
    }

    /** Makes [origin] the address the app talks to, once the worker answers there; the login stays. */
    fun use(origin: String) = switch(origin, AddressTask.Switching(origin)) { _, _ -> }

    private fun switch(origin: String, task: AddressTask, then: suspend (CloudflareApi, Addresses) -> Unit) {
        val p = home.phase as? Phase.SignedIn ?: return
        val session = p.session ?: return
        val a = addresses ?: return
        this.task = task
        p.scope.launch {
            try {
                val s = signInWithCloudflare(origin, session.login, home.platform.name, fresh = false, home::sessionFor, engine)
                val next = try {
                    CloudflareApi(session, engine).use { then(it, a) }
                    serverFacts(s.api, s.session, engine)
                } catch (e: Exception) {
                    s.api.close()
                    throw e
                }
                Snapshot.withMutableSnapshot {
                    home.switchedTo(s, next)
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

    private fun work(task: AddressTask, block: suspend (CloudflareApi, Addresses) -> AddressTask?) {
        val p = home.phase as? Phase.SignedIn ?: return
        val session = p.session ?: return
        val f = facts ?: return
        val a = addresses ?: return
        this.task = task
        p.scope.launch {
            try {
                CloudflareApi(session, engine).use { cf ->
                    val next = block(cf, a)
                    val now = cf.addresses(a.account)
                    Snapshot.withMutableSnapshot {
                        f.update { it.copy(addresses = now) }
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

    /** DNS records stand in the way of [conflict]'s hostname; deleted only if the user agrees. */
    data class Conflict(val conflict: DomainOutcome.Conflict) : AddressTask

    /** Taking the worker off [origin], asked first, then [confirmed]; [moveTo] is where the app goes first. */
    data class Removing(val origin: String, val confirmed: Boolean, val moveTo: String?) : AddressTask

    /** Making [origin] the address the app uses, once it answers there. */
    data class Switching(val origin: String) : AddressTask

    data class Failed(val message: String) : AddressTask
}
