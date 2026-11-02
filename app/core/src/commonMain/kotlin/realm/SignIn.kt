package realm

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException

/** What logging in with Cloudflare came to. */
sealed interface LoggedIn

/** Signed in as a server's owner; save [connection] to sign in again, and [session] is set when signed in with Cloudflare. */
class SignedIn(val api: Api, val info: Info, val realms: List<Realm>, val connection: Connection.Owner, val session: CloudflareSession?) :
    LoggedIn

/** Logged in to one account with no realm worker yet; [login] stays valid for deploying, then [signInWithCloudflare]. */
class NoWorker(val login: CloudflareLogin, val account: Account) : LoggedIn

/** Logged in to several [accounts], for the user to choose from; [login] is for none of them yet. */
class SeveralAccounts(val login: CloudflareLogin, val accounts: List<Account>) : LoggedIn

/** A share link connected to: [url] as it's saved, and what the link says about itself. */
class Connected(val url: String, val api: Api, val info: Info)

/** Whether [input] is a share link, …/s/<token>, rather than a server's address. */
fun isLink(input: String): Boolean = "/s/" in input

/** A server's address as typed: https:// unless it says otherwise, and without the /api its API is under. */
fun serverOrigin(input: String): String = withScheme(input).trimEnd('/').removeSuffix("/api").trimEnd('/')

/** A share link as typed or pasted, https:// unless it says otherwise; null if [input] isn't one. */
fun linkUrl(input: String): String? = withScheme(input).trimEnd('/').takeIf(::isLink)

private fun withScheme(input: String) = input.trim().let {
    if (it.startsWith("http://") ||
        it.startsWith("https://")
    ) {
        it
    } else {
        "https://$it"
    }
}

suspend fun signInWithToken(server: String, token: String, source: String, engine: HttpClientEngine? = null): SignedIn {
    if (isLink(server)) throw ApiException("That's a share link. Connect to it instead.")
    val origin = serverOrigin(server)
    val api = Api.server(origin, Bearer { token }, source, engine)
    val (info, realms) = inWords {
        api.closingOnFailure {
            val info = try {
                api.info()
            } catch (e: ApiException) {
                if (e.status == 404) throw ApiException("There's no realm server at that address.", 404)
                throw e
            }
            if (info.scope != "owner") throw ApiException("not an owner token")
            info to api.realms()
        }
    }
    return SignedIn(api, info, realms, Connection.ByToken(origin, Secret(token)), null)
}

/** Logs in with Cloudflare in the browser; [session] makes the connection's login a bearer whose renewals the caller saves. */
suspend fun logInWithCloudflare(
    browser: Browser,
    source: String,
    session: (Connection.ByCloudflare) -> CloudflareSession = { CloudflareSession(it.login) {} },
    engine: HttpClientEngine? = null,
): LoggedIn = inWords {
    val login = Cloudflare.logIn(browser, engine)
    val accounts = CloudflareApi(Bearer { login.access.value }, engine).use { it.accounts() }
    val only = when (accounts.size) {
        0 -> throw ApiException("No Cloudflare account was picked on the consent screen")
        1 -> accounts[0]
        else -> return@inWords SeveralAccounts(login, accounts)
    }
    val worker = when (val on = CloudflareApi(Bearer { login.access.value }, engine).use { it.realmOn(only) }) {
        RealmOn.None -> return@inWords NoWorker(login.copy(account = only.id), only)
        is RealmOn.NoAddress -> throw ApiException(on.why)
        is RealmOn.Running -> on.worker
    }
    signInTo(worker, CloudflareSession(login) {}, source, session, engine)
}

/** Signs in to the realm worker on [account], such as one picked from [SeveralAccounts]; [session] is as for [logInWithCloudflare]. */
suspend fun signInOn(
    account: Account,
    login: CloudflareSession,
    source: String,
    session: (Connection.ByCloudflare) -> CloudflareSession = { CloudflareSession(it.login) {} },
    engine: HttpClientEngine? = null,
): SignedIn = inWords {
    val worker = when (val on = CloudflareApi(login, engine).use { it.realmOn(account) }) {
        is RealmOn.Running -> on.worker
        is RealmOn.NoAddress -> throw ApiException(on.why)
        RealmOn.None -> throw ApiException("There's no realm worker on ${account.name.ifBlank { account.id }}.")
    }
    signInTo(worker, login, source, session, engine)
}

private suspend fun signInTo(
    worker: Worker,
    login: CloudflareSession,
    source: String,
    session: (Connection.ByCloudflare) -> CloudflareSession,
    engine: HttpClientEngine?,
): SignedIn {
    CloudflareApi(login, engine).use { it.claim(worker.account) }
    return signInWithCloudflare(worker.origin, login.login.copy(account = worker.account), source, session, engine)
}

/** Signs in to the realm worker at [origin] with a Cloudflare [login] for its account; [session] is as for [logInWithCloudflare]. */
suspend fun signInWithCloudflare(
    origin: String,
    login: CloudflareLogin,
    source: String,
    session: (Connection.ByCloudflare) -> CloudflareSession = { CloudflareSession(it.login) {} },
    engine: HttpClientEngine? = null,
): SignedIn = inWords {
    val s = session(Connection.ByCloudflare(origin, login))
    val api = Api.server(origin, s, source, engine)
    val (info, realms) = api.closingOnFailure { Cloudflare.checkOwner(api, fresh = true) to api.realms() }
    SignedIn(api, info, realms, Connection.ByCloudflare(origin, s.login), s)
}

/** Connects to a share link; spends none of its uses. */
suspend fun connectLink(link: String, source: String, engine: HttpClientEngine? = null): Connected {
    val url = linkUrl(link) ?: throw ApiException("That's not a share link")
    val api = Api(url, null, source, engine)
    val info = inWords { api.closingOnFailure { api.info() } }
    return Connected(url, api, info)
}

/** An error in words that say what to do. */
fun explain(e: Exception): String = when (e.message) {
    "not found" -> "That isn't there anymore."
    "bad token" -> "That token doesn't work for this server."
    else -> e.message ?: "Can't reach the server"
}

private suspend fun <T> inWords(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: CloudflareRejected) {
    throw e
} catch (e: ApiException) {
    val words = explain(e)
    throw if (words == e.message) e else ApiException(words, e.status, e)
} catch (e: Exception) {
    throw ApiException(explain(e), cause = e)
}

internal suspend fun <T> Api.closingOnFailure(block: suspend () -> T): T = try {
    block()
} catch (e: Throwable) {
    close()
    throw e
}
