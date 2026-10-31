// Getting in: signing in to your own server, logging in with Cloudflare, and connecting a share link. Each
// does every step first and returns the whole result, so a caller can show it in one go.
package realm

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException

/** What logging in with Cloudflare came to: signed in, or logged in with no realm worker to sign in to yet. */
sealed interface LoggedIn

/**
 * Signed in as a server's owner, with its realms already fetched. Save [connection] to sign in again next
 * time. [session] is the Cloudflare login as it renews itself, when signed in with one.
 */
class SignedIn(val api: Api, val info: Info, val realms: List<Realm>, val connection: Connection.Owner, val session: CloudflareSession?) :
    LoggedIn

/**
 * Logged in with Cloudflare, but none of [accounts], the ones picked on the consent screen, has a realm
 * worker yet. [login] still works: deploy one with it, then [signInWithCloudflare], with no second consent.
 */
class NoWorker(val login: CloudflareLogin, val accounts: List<Account>) : LoggedIn

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

/** Signs in to your own server at [server] with its owner [token], as `realm login <url> <token>` does. */
suspend fun signInWithToken(server: String, token: String, source: String, engine: HttpClientEngine? = null): SignedIn {
    if (isLink(server)) throw ApiException("That's a share link. Connect to it instead.")
    val origin = serverOrigin(server)
    val api = Api.server(origin, Bearer { token }, source, engine)
    val (info, realms) = inWords {
        api.closingOnFailure {
            val info = try {
                api.info()
            } catch (e: ApiException) {
                // A worker from before realms has no /info.
                if (e.status == 404) throw ApiException("That server needs updating: redeploy the worker.", 404)
                throw e
            }
            if (info.scope != "owner") throw ApiException("not an owner token")
            info to api.realms()
        }
    }
    return SignedIn(api, info, realms, Connection.ByToken(origin, token), null)
}

/**
 * Logs in with Cloudflare in the browser and signs in to the realm worker on that account, as `realm login`
 * does: no URL or token to copy, the login is the owner's token. With realm workers on more than one
 * account, [choose] picks one; null from it gives up, and so does this. With none, it's [NoWorker], to
 * deploy one. [session] makes the connection's login a bearer that keeps its renewals, as the caller saves
 * them. [engine] is the platform's own unless one is given, as previews do.
 */
suspend fun logInWithCloudflare(
    browser: Browser,
    source: String,
    choose: suspend (List<Worker>) -> Worker?,
    session: (Connection.ByCloudflare) -> CloudflareSession = { CloudflareSession(it.login) {} },
    engine: HttpClientEngine? = null,
): LoggedIn? = inWords {
    val login = Cloudflare.logIn(browser, engine)
    val found = Cloudflare.workers(login.access, engine)
    val worker = when (found.size) {
        0 -> return@inWords NoWorker(login, CloudflareApi(Bearer { login.access }, engine).use { it.accounts() })
        1 -> found[0]
        else -> choose(found) ?: return@inWords null
    }
    // The worker takes a Cloudflare login as its owner's once it knows which account it's on.
    Cloudflare.claim(login.access, worker, engine)
    signInWithCloudflare(worker.origin, login.copy(account = worker.account), source, session, engine)
}

/**
 * Signs in to the realm worker at [origin] with a Cloudflare [login] for its account, as logging in does once
 * it has found the worker: one just deployed, say. [session] is as for [logInWithCloudflare].
 */
suspend fun signInWithCloudflare(
    origin: String,
    login: CloudflareLogin,
    source: String,
    session: (Connection.ByCloudflare) -> CloudflareSession = { CloudflareSession(it.login) {} },
    engine: HttpClientEngine? = null,
): SignedIn = inWords {
    val s = session(Connection.ByCloudflare(origin, login))
    val api = Api.server(origin, s, source, engine)
    val (info, realms) = api.closingOnFailure { Cloudflare.checkOwner(api) to api.realms() }
    SignedIn(api, info, realms, Connection.ByCloudflare(origin, s.login), s)
}

/** Connects to a share link, as `realm connect <link>` does. Asking what it is spends none of its uses. */
suspend fun connectLink(link: String, source: String, engine: HttpClientEngine? = null): Connected {
    val url = linkUrl(link) ?: throw ApiException("That's not a share link")
    val api = Api(url, null, source, engine)
    val info = inWords { api.closingOnFailure { api.info() } }
    return Connected(url, api, info)
}

/** An error in words that say what to do. */
fun explain(e: Exception): String = when (e.message) {
    // A worker from before realms has no /info.
    "not found" -> "That server needs updating: redeploy the worker."

    "bad token" -> "That token doesn't work for this server."

    else -> e.message ?: "Can't reach the server"
}

/** Runs [block], putting whatever goes wrong into an [ApiException] that [explain]s it. */
private suspend fun <T> inWords(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw ApiException(explain(e), (e as? ApiException)?.status, e)
}

/** Runs [block] with this API, closing it if [block] fails, as nothing else will hold it then. */
internal suspend fun <T> Api.closingOnFailure(block: suspend () -> T): T = try {
    block()
} catch (e: Throwable) {
    close()
    throw e
}
