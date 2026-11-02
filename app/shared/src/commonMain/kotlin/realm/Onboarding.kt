package realm

import androidx.compose.runtime.snapshots.Snapshot
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration.Companion.seconds

class Onboarding internal constructor(private val home: Home, private val scope: CoroutineScope, private val engine: HttpClientEngine?) {
    /**
     * Where onboarding with [session] has got to: [account] is the id of the account chosen to deploy to, null
     * while choosing, and [realms] whether realm runs on each account, by id. [scope] runs the work for the
     * session, ended with it.
     */
    data class State(
        val session: CloudflareSession,
        val scope: CoroutineScope,
        val account: String?,
        val realms: Map<String, Fetched<RealmOn>> = emptyMap(),
        val problem: String? = null,
        val deployFailed: Boolean = false,
        val comingUp: String? = null,
        val busy: Boolean = false,
        val subdomain: String = "",
        val zone: Zone? = null,
        val name: String = "realm",
    )

    private val at: State? get() = (home.phase as? Phase.Onboarding)?.at

    private fun edit(s: CloudflareSession? = session, change: State.() -> State) {
        val now = at ?: return
        if (now.session === s) home.phase = Phase.Onboarding(now.change())
    }

    /** The Cloudflare login while it's all there is; null otherwise. */
    val session: CloudflareSession? get() = at?.session

    /** The accounts the login was given on the consent screen. */
    val accounts = Fetched<List<Account>>(scope, "Looking at your Cloudflare accounts", failed = ::failed)

    /** Whether realm runs on each account, by id. */
    val realms: Map<String, Fetched<RealmOn>> get() = at?.realms.orEmpty()

    /** The account chosen to deploy to, with no realm on it yet, named once [accounts] are known; null while choosing. */
    val account: Account? get() = at?.account?.let { id -> accounts.state.value?.firstOrNull { it.id == id } ?: Account(id) }

    private val lastUsed = Remembered(home.platform.prefs, "cloudflare-last", String.serializer())

    /** What deploying to [account] needs, each requirement checked. */
    val requirements = Fetched<Requirements>(scope, "Checking what deploying realm needs", failed = ::failed)

    /** Why checking or the last deploy failed; null when nothing did. */
    val problem: String? get() = at?.problem

    /** Whether [problem] is from deploying, so trying again is a retry. */
    val deployFailed: Boolean get() = at?.deployFailed == true

    /** The origin deployed to that doesn't answer yet; signing in is tried again meanwhile. */
    val comingUp: String? get() = at?.comingUp

    /** A deploy or a sign-in is under way. */
    val busy: Boolean get() = at?.busy == true

    /** The workers.dev subdomain typed, for an account that has none. */
    var subdomain: String
        get() = at?.subdomain.orEmpty()
        set(value) = edit { copy(subdomain = value) }

    /** The domain to put realm on; null is workers.dev. */
    var zone: Zone?
        get() = at?.zone
        set(value) = edit { copy(zone = value) }

    /** The subdomain of [zone] typed; blank for the domain itself. */
    var name: String
        get() = at?.name.orEmpty()
        set(value) = edit { copy(name = value) }

    val choices get() = Choices(subdomain = subdomain, domain = zone?.let { DomainChoice(it, name) })

    /** What Deploy deploys; null until every requirement is met and the address is valid. */
    val deployment: Deployment? get() = requirements.state.value?.deployment(choices)

    /** [list] with the account used last first. */
    fun ordered(list: List<Account>): List<Account> = lastUsed.get().let { last -> list.sortedByDescending { it.id == last } }

    fun wasLast(a: Account) = lastUsed.get() == a.id

    internal fun start(login: CloudflareLogin, known: List<Account>?) {
        val s = home.sessionFor(login) { Connection.LoginOnly(it.copy(account = at?.account)) }
        at?.let {
            it.scope.cancel()
            it.session.close()
        }
        Snapshot.withMutableSnapshot {
            forget()
            home.phase = Phase.Onboarding(State(s, home.childScope(), login.account))
            save(login)
            if (known != null) accounts.set(known)
            if (login.account != null) {
                check()
            } else if (known != null) {
                askRealms(known)
            }
        }
        if (known == null) loadAccounts(s)
    }

    private fun save(login: CloudflareLogin) = home.platform.prefs.saveOwn(Connection.LoginOnly(login.copy(account = at?.account)))

    private fun loadAccounts(s: CloudflareSession) {
        edit(s) { copy(problem = null) }
        at?.scope?.launch {
            val list = try {
                CloudflareApi(s, engine).use { it.accounts() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Looking at your Cloudflare accounts", e)
                edit(s) { copy(problem = explain(e)) }
                return@launch
            }
            Snapshot.withMutableSnapshot {
                accounts.set(list)
                if (at?.account == null) askRealms(list)
            }
        }
    }

    private fun askRealms(list: List<Account>) {
        val now = at ?: return
        val asked = list.associate { a ->
            a.id to Fetched<RealmOn>(now.scope, "Looking for realm on ${a.id}", failed = ::failed).apply {
                refresh { CloudflareApi(now.session, engine).use { it.realmOn(a) } }
            }
        }
        edit(now.session) { copy(realms = asked) }
    }

    /** Signs in to realm on [a] if it runs there, else picks it to deploy to; either way it's offered first next time. */
    fun choose(a: Account) {
        val on = realms[a.id]?.state?.value ?: return
        if (busy) return
        lastUsed.set(a.id)
        if (on != RealmOn.None) return signInToIt(a)
        Snapshot.withMutableSnapshot {
            requirements.forget()
            edit { copy(account = a.id, zone = null, problem = null, deployFailed = false) }
            session?.let { save(it.login) }
        }
        check()
    }

    /** Back to choosing an account, with the same login: no new consent in the browser. */
    fun pickAnother() {
        val s = session ?: return
        if (busy) return
        Snapshot.withMutableSnapshot {
            requirements.forget()
            edit { copy(account = null, problem = null, deployFailed = false) }
            save(s.login)
            accounts.state.value?.let(::askRealms)
        }
        if (accounts.state == Loaded.Unknown) loadAccounts(s)
    }

    /** Checks the requirements, or each account's realm, again; what's known stays meanwhile. */
    fun check() {
        val s = session ?: return
        edit { copy(problem = null, deployFailed = false) }
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
        edit { copy(busy = true, problem = null) }
        home.cloudflare.deployFresh(s, d) { why -> stop(s, why, deployFailed = true) }
    }

    /** Signs in to the realm worker on [a], as when it was deployed some other way since the check. */
    fun signInToIt(a: Account? = account) {
        val s = session ?: return
        if (a == null || busy) return
        edit { copy(busy = true, problem = null) }
        at?.scope?.launch {
            val signed = try {
                signInOn(a, s, home.platform.name, home::sessionFor, engine)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Signing in to the worker on ${a.id}", e)
                edit(s) { copy(busy = false, problem = explain(e)) }
                return@launch
            }
            Snapshot.withMutableSnapshot { home.signedIn(signed) }
        }
    }

    internal fun comingUp(deployed: Connection.ByCloudflare) {
        val s = session ?: return
        edit { copy(comingUp = deployed.server, busy = true, problem = null, deployFailed = false) }
        at?.scope?.launch {
            val signed = try {
                answering(deployed, s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Signing in to ${deployed.server}", e)
                stop(s, explain(e))
                return@launch
            }
            if (signed != null) {
                Snapshot.withMutableSnapshot { home.signedIn(signed) }
            } else {
                val host = deployed.server.removePrefix("https://")
                stop(s, "realm is deployed, but $host doesn't answer yet. Sign in again in a few minutes.")
            }
        }
    }

    private suspend fun answering(deployed: Connection.ByCloudflare, s: CloudflareSession): SignedIn? {
        repeat(COMING_UP_TRIES) {
            delay(COMING_UP_EVERY)
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

    private fun stop(s: CloudflareSession, why: String, deployFailed: Boolean = false) {
        if (session !== s) return
        Snapshot.withMutableSnapshot {
            check()
            edit(s) { copy(comingUp = null, busy = false, problem = why, deployFailed = deployFailed) }
        }
    }

    internal fun forget() {
        accounts.forget()
        requirements.forget()
    }

    private fun failed(message: String) = edit { copy(problem = message) }

    private companion object {
        val COMING_UP_EVERY = 15.seconds
        const val COMING_UP_TRIES = 40
    }
}
