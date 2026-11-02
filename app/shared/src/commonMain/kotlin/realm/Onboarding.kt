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
    /** The Cloudflare login while it's all there is; null otherwise. */
    var session by mutableStateOf<CloudflareSession?>(null)
        private set

    /** The accounts the login was given on the consent screen. */
    val accounts = Fetched<List<Account>>(scope, "Looking at your Cloudflare accounts", failed = ::failed)

    /** Whether realm runs on each account, by id. */
    var realms by mutableStateOf<Map<String, Fetched<RealmOn>>>(emptyMap())
        private set

    /** The account chosen to deploy to, with no realm on it yet; null while choosing. */
    var account by mutableStateOf<Account?>(null)
        private set

    private val lastUsed = Remembered(home.platform.prefs, "cloudflare-last", String.serializer())

    /** What deploying to [account] needs, each requirement checked. */
    val requirements = Fetched<Requirements>(scope, "Checking what deploying realm needs", failed = ::failed)

    /** Why checking or the last deploy failed; null when nothing did. */
    var problem by mutableStateOf<String?>(null)
        private set

    /** Whether [problem] is from deploying, so trying again is a retry. */
    var deployFailed by mutableStateOf(false)
        private set

    /** The origin deployed to that doesn't answer yet; signing in is tried again meanwhile. */
    var comingUp by mutableStateOf<String?>(null)
        private set

    /** A deploy or a sign-in is under way. */
    var busy by mutableStateOf(false)
        private set

    /** The workers.dev subdomain typed, for an account that has none. */
    var subdomain by mutableStateOf("")

    /** The domain to put realm on; null is workers.dev. */
    var zone by mutableStateOf<Zone?>(null)

    /** The subdomain of [zone] typed; blank for the domain itself. */
    var name by mutableStateOf("realm")

    val choices get() = Choices(subdomain = subdomain, domain = zone?.let { DomainChoice(it, name) })

    /** What Deploy deploys; null until every requirement is met and the address is valid. */
    val deployment: Deployment? get() = requirements.state.value?.deployment(choices)

    /** [list] with the account used last first. */
    fun ordered(list: List<Account>): List<Account> = lastUsed.get().let { last -> list.sortedByDescending { it.id == last } }

    fun wasLast(a: Account) = lastUsed.get() == a.id

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

    private fun save(login: CloudflareLogin) = home.platform.prefs.saveOwn(Connection.LoginOnly(login.copy(account = account?.id)))

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

    private fun askRealms(list: List<Account>) {
        val s = session ?: return
        realms = list.associate { a ->
            a.id to Fetched<RealmOn>(scope, "Looking for realm on ${a.id}", failed = ::failed).apply {
                refresh { CloudflareApi(s, engine).use { it.realmOn(a) } }
            }
        }
    }

    /** Signs in to realm on [a] if it runs there, else picks it to deploy to; either way it's offered first next time. */
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

    /** Checks the requirements, or each account's realm, again; what's known stays meanwhile. */
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

    /** Deploys, if every requirement is met; success signs in to it. */
    fun deploy() {
        val s = session ?: return
        val d = deployment ?: return
        if (busy) return
        busy = true
        problem = null
        home.cloudflare.deployFresh(s, d) { why ->
            Snapshot.withMutableSnapshot {
                busy = false
                check()
                problem = why
                deployFailed = true
            }
        }
    }

    /** Signs in to the realm worker on [a], as when it was deployed some other way since the check. */
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

    internal fun comingUp(deployed: Connection.ByCloudflare) {
        val s = session ?: return
        Snapshot.withMutableSnapshot {
            comingUp = deployed.server
            busy = true
            problem = null
            deployFailed = false
        }
        scope.launch {
            val signed = try {
                answering(deployed, s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Signing in to ${deployed.server}", e)
                if (session === s) stopComingUp(explain(e))
                return@launch
            }
            when {
                session !== s -> signed?.api?.close()

                signed != null -> Snapshot.withMutableSnapshot { home.signedIn(signed) }

                else -> {
                    val host = deployed.server.removePrefix("https://")
                    stopComingUp("realm is deployed, but $host doesn't answer yet. Sign in again in a few minutes.")
                }
            }
        }
    }

    private suspend fun answering(deployed: Connection.ByCloudflare, s: CloudflareSession): SignedIn? {
        repeat(COMING_UP_TRIES) {
            delay(COMING_UP_EVERY)
            if (session !== s) return null
            try {
                return signInWithCloudflare(
                    deployed.server,
                    s.login.copy(account = deployed.login.account),
                    home.platform.name,
                    fresh = true,
                    home::sessionFor,
                    engine,
                )
            } catch (e: ApiException) {
                if (!e.mayPassLater) throw e
            }
        }
        return null
    }

    private fun stopComingUp(why: String) = Snapshot.withMutableSnapshot {
        comingUp = null
        busy = false
        check()
        problem = why
    }

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
        val COMING_UP_EVERY = 15.seconds
        const val COMING_UP_TRIES = 40
    }
}
