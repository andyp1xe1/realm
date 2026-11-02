// Logged in with Cloudflare, but not signed in to a realm: the login is kept as a Connection.LoginOnly, from run
// to run, with the account chosen. With several accounts the user chooses one, each shown with whether realm runs
// there; on one without, onboarding checks what deploying needs and deploys once all of it is there. A failed
// deploy keeps the login; only signing out ends it.
package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration.Companion.seconds

class Onboarding internal constructor(private val home: Home, private val scope: CoroutineScope, private val engine: HttpClientEngine?) {
    /** The Cloudflare login, as it renews itself, while it's all there is; null otherwise. */
    var session by mutableStateOf<CloudflareSession?>(null)
        private set

    /** The accounts the login was given on the consent screen. */
    val accounts = Fetched<List<Account>>(scope, "Looking at your Cloudflare accounts", failed = ::failed)

    /** Whether realm runs on each account, by id, asked as soon as the accounts are there to choose from. */
    var realms by mutableStateOf<Map<String, Fetched<RealmOn>>>(emptyMap())
        private set

    /** The account chosen, with no realm on it yet, to deploy to; null while choosing. */
    var account by mutableStateOf<Account?>(null)
        private set

    /** The account chosen last time, offered first; kept after signing out, for the next login. */
    private val lastUsed = Remembered(home.platform.prefs, "cloudflare-last", String.serializer())

    /** What deploying to [account] needs, each requirement checked. */
    val requirements = Fetched<Requirements>(scope, "Checking what deploying realm needs", failed = ::failed)

    /** Why checking or the last deploy failed, to fix and try again; null when nothing did. */
    var problem by mutableStateOf<String?>(null)
        private set

    /** Whether [problem] is from deploying, so trying again is a retry. */
    var deployFailed by mutableStateOf(false)
        private set

    /**
     * Deployed, answering at this origin soon: a new address takes a few minutes to reach you, so signing in
     * is tried again meanwhile. Null otherwise.
     */
    var comingUp by mutableStateOf<String?>(null)
        private set

    /** A deploy or a sign-in is under way. */
    var busy by mutableStateOf(false)
        private set

    /** The workers.dev subdomain typed, for an account that has none. */
    var subdomain by mutableStateOf("")

    /** The domain to put realm on; null is workers.dev. */
    var zone by mutableStateOf<Zone?>(null)

    /** The subdomain of [zone] typed: realm unless changed, blank for the domain itself. */
    var name by mutableStateOf("realm")

    val choices get() = Choices(subdomain = subdomain, domain = zone?.let { DomainChoice(it, name) })

    /** What Deploy deploys: null, and Deploy dimmed, until every requirement is met and the address is valid. */
    val deployment: Deployment? get() = requirements.state.value?.deployment(choices)

    /** [list] in the order the chooser offers them: the one used last first. */
    fun ordered(list: List<Account>): List<Account> = lastUsed.get().let { last -> list.sortedByDescending { it.id == last } }

    /** Whether [a] was the account chosen last time. */
    fun wasLast(a: Account) = lastUsed.get() == a.id

    /**
     * Keeps [login] as the one thing signed in, here and in prefs for next time: for its account, if it has
     * one, whose requirements are checked; else to choose one. [known] are its accounts when logging in has
     * them already; otherwise they're asked for.
     */
    internal fun start(login: CloudflareLogin, known: List<Account>?) {
        lateinit var s: CloudflareSession
        s = CloudflareSession(login) { next ->
            if (session !== s) return@CloudflareSession
            if (next != null) {
                save(next)
            } else {
                scope.launch {
                    if (session !== s) return@launch
                    home.signOut()
                    home.message = CloudflareRejected().message
                }
            }
        }
        Snapshot.withMutableSnapshot {
            handOver()
            session = s
            val id = login.account
            account = id?.let { known?.firstOrNull { a -> a.id == id } ?: Account(id) }
            save(login)
            if (known != null) accounts.set(known)
            if (account != null) {
                check()
            } else if (known != null) {
                askRealms(known)
            }
        }
        if (known == null) loadAccounts(s)
    }

    /** Keeps [login] as a [Connection.LoginOnly] for the account chosen, if one is. */
    private fun save(login: CloudflareLogin) = home.platform.prefs.saveOwn(Connection.LoginOnly(login.copy(account = account?.id)))

    /** Asks for the login's accounts; while choosing, then whether realm runs on each. */
    private fun loadAccounts(s: CloudflareSession) {
        problem = null
        scope.launch {
            val list = try {
                CloudflareApi(s, engine).use { it.accounts() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Looking at your Cloudflare accounts", e)
                if (session === s) problem = explain(e)
                return@launch
            }
            if (session !== s) return@launch
            Snapshot.withMutableSnapshot {
                accounts.set(list)
                if (account == null) askRealms(list)
            }
        }
    }

    /** Asks whether realm runs on each of [list], each shown as soon as it's known. */
    private fun askRealms(list: List<Account>) {
        val s = session ?: return
        realms = list.associate { a ->
            a.id to Fetched<RealmOn>(scope, "Looking for realm on ${a.id}", failed = ::failed).apply {
                refresh { CloudflareApi(s, engine).use { it.realmOn(a) } }
            }
        }
    }

    /**
     * Chooses [a]: signs in to realm there if it runs there; else it's the account to deploy to, and what
     * that needs is checked. Either way it's offered first next time.
     */
    fun choose(a: Account) {
        val on = realms[a.id]?.state?.value ?: return
        if (busy) return
        lastUsed.set(a.id)
        if (on != RealmOn.None) return signInToIt(a)
        Snapshot.withMutableSnapshot {
            account = a
            session?.let { save(it.login) }
            requirements.forget()
            zone = null
            problem = null
            deployFailed = false
        }
        check()
    }

    /** Back to choosing an account, with the same login: no new consent in the browser. */
    fun pickAnother() {
        val s = session ?: return
        if (busy) return
        Snapshot.withMutableSnapshot {
            account = null
            save(s.login)
            requirements.forget()
            problem = null
            deployFailed = false
            accounts.state.value?.let(::askRealms)
        }
        if (accounts.state == Loaded.Unknown) loadAccounts(s)
    }

    /** Checks again: the requirements, as after turning on R2, or each account's realm; what's known stays meanwhile. */
    fun check() {
        val s = session ?: return
        problem = null
        deployFailed = false
        val a = account
        when {
            a != null -> requirements.refresh { CloudflareApi(s, engine).use { it.requirements(a) } }
            accounts.state == Loaded.Unknown -> loadAccounts(s)
            else -> accounts.state.value?.let(::askRealms)
        }
    }

    /** Deploys, if every requirement is met; the sheet shows its steps, and success signs in to it. */
    fun deploy() {
        val s = session ?: return
        val d = deployment ?: return
        if (busy) return
        busy = true
        problem = null
        home.cloudflare.deployFresh(s, d) { why ->
            Snapshot.withMutableSnapshot {
                busy = false
                check() // what stopped it, like R2 turned off, shows in its row as well
                problem = why
                deployFailed = true
            }
        }
    }

    /** Signs in to the realm worker on [a], chosen, or deployed some other way since the check. */
    fun signInToIt(a: Account? = account) {
        val s = session ?: return
        if (a == null || busy) return
        busy = true
        problem = null
        scope.launch {
            val signed = try {
                signInOn(a, s, home.platform.name, home::sessionFor, engine)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Signing in to the worker on ${a.id}", e)
                Snapshot.withMutableSnapshot {
                    busy = false
                    problem = explain(e)
                }
                return@launch
            }
            if (session === s) Snapshot.withMutableSnapshot { home.signedIn(signed) }
        }
    }

    /**
     * The deploy is done, but [deployed]'s address doesn't answer yet, as a brand-new one may not for minutes
     * while its certificate is issued. Stays logged in and tries signing in again every little while, for
     * about ten minutes; then says so, for the user to sign in later.
     */
    internal fun comingUp(deployed: Connection.ByCloudflare) {
        val s = session ?: return
        Snapshot.withMutableSnapshot {
            comingUp = deployed.server
            busy = true
            problem = null
            deployFailed = false
        }
        scope.launch {
            repeat(COMING_UP_TRIES) {
                delay(COMING_UP_EVERY)
                if (session !== s) return@launch
                val signed = try {
                    signInWithCloudflare(
                        deployed.server,
                        s.login.copy(account = deployed.login.account),
                        home.platform.name,
                        home::sessionFor,
                        engine,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // not there yet
                }
                if (signed != null) {
                    if (session === s) Snapshot.withMutableSnapshot { home.signedIn(signed) } else signed.api.close()
                    return@launch
                }
            }
            if (session !== s) return@launch
            Snapshot.withMutableSnapshot {
                comingUp = null
                busy = false
                check()
                problem =
                    "realm is deployed, but ${deployed.server.removePrefix("https://")} doesn't answer yet. Sign in again in a few minutes."
            }
        }
    }

    /**
     * Forgets the login here, as it goes on as a server's, or is replaced, or is ended by signing out, which
     * this returns it for.
     */
    internal fun handOver(): CloudflareSession? {
        val s = session
        session = null
        accounts.forget()
        realms = emptyMap()
        requirements.forget()
        account = null
        problem = null
        deployFailed = false
        comingUp = null
        busy = false
        subdomain = ""
        zone = null
        name = "realm"
        return s
    }

    private fun failed(message: String) {
        if (session != null) problem = message
    }

    private companion object {
        /** How often, and how many times, a deployed address that doesn't answer yet is tried again: ten minutes. */
        val COMING_UP_EVERY = 15.seconds
        const val COMING_UP_TRIES = 40
    }
}
