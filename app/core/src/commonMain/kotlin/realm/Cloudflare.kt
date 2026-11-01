// Logging in with Cloudflare, as the CLI does: OAuth with PKCE against realm's Cloudflare client, caught on
// localhost, then finding the realm worker on the chosen account. The login is then the owner's token.
package realm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.forms.submitForm
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import io.ktor.util.encodeBase64
import io.ktor.util.generateNonce
import io.ktor.util.sha256
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/** The browser, for logins that come back to localhost. Implemented by each app's Platform. */
interface Browser {
    /**
     * Opens [url] in the browser and waits for it to come back to http://localhost:[port][path], as an OAuth
     * login does. Returns the query parameters it came back with.
     */
    suspend fun authorize(url: String, port: Int, path: String): Map<String, String>
}

/** A Cloudflare login, kept the way the CLI keeps it. */
@Serializable
data class CloudflareLogin(
    val access: String,
    val refresh: String? = null,
    /** Unix seconds. */
    val expires: Long,
    val account: String? = null,
)

/** A realm worker found on a Cloudflare account. */
data class Worker(val account: String, val accountName: String, val origin: String)

/** Cloudflare turned the login down: the only way on is logging in again. */
class CloudflareRejected : Exception("Your Cloudflare login has expired. Log in again.")

object Cloudflare {
    private const val CLIENT_ID = "e48e69845742def220449dec54f713b3" // realm's OAuth client; public, not a secret
    private const val AUTHORIZE = "https://dash.cloudflare.com/oauth2/auth"
    private const val TOKEN = "https://dash.cloudflare.com/oauth2/token"
    private const val REVOKE = "https://dash.cloudflare.com/oauth2/revoke"
    private const val PORT = 53134 // registered as http://localhost:53134/oauth/callback
    private const val CALLBACK = "/oauth/callback"
    private const val REDIRECT = "http://localhost:$PORT$CALLBACK"
    const val WORKER = "realm"

    /** The same scopes as the CLI's; offline_access is what gets a refresh token, as access tokens last an hour. */
    private val scopes = listOf(
        "account-settings.read",
        "workers-scripts.admin",
        "d1.write",
        "workers-r2.write",
        "workers-routes.write",
        "dns.write",
        "zone.read",
        "offline_access",
    )

    private val http by lazy { client(null) }

    /** The browser login: authorization code with PKCE, caught on localhost. */
    suspend fun logIn(browser: Browser, engine: HttpClientEngine? = null): CloudflareLogin {
        val verifier = generateNonce() + generateNonce()
        val challenge = sha256(verifier.encodeToByteArray()).encodeBase64().trimEnd('=').replace('+', '-').replace('/', '_')
        val state = generateNonce()
        val url = URLBuilder(AUTHORIZE).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", CLIENT_ID)
            parameters.append("redirect_uri", REDIRECT)
            parameters.append("scope", scopes.joinToString(" "))
            parameters.append("state", state)
            parameters.append("code_challenge", challenge)
            parameters.append("code_challenge_method", "S256")
        }.buildString()
        val back = browser.authorize(url, PORT, CALLBACK)
        when {
            back["state"] != state -> throw ApiException("That login didn't match. Try again.")
            back["error"] != null -> throw ApiException("Cloudflare said ${back["error_description"] ?: back["error"]}")
        }
        val code = back["code"] ?: throw ApiException("Cloudflare didn't send a login back. Try again.")
        return exchange(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT,
                "client_id" to CLIENT_ID,
                "code_verifier" to verifier,
            ),
            keep = null,
            engine,
        )
    }

    /** A fresh access token for [login]. Refresh tokens are single-use, so the result replaces [login]. */
    suspend fun refresh(login: CloudflareLogin): CloudflareLogin {
        val refresh = login.refresh ?: throw CloudflareRejected()
        return exchange(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to CLIENT_ID), keep = refresh)
            .copy(account = login.account)
    }

    /** Ends the login on Cloudflare's side too. Best effort: signing out goes ahead either way. */
    suspend fun revoke(login: CloudflareLogin) {
        val refresh = login.refresh ?: return
        runCatching {
            http.submitForm(
                REVOKE,
                parameters {
                    append("client_id", CLIENT_ID)
                    append("token_type_hint", "refresh_token")
                    append("token", refresh)
                },
            )
        }
    }

    @Serializable
    private data class TokenReply(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 0,
        val error: String? = null,
        @SerialName("error_description") val description: String? = null,
    )

    /** Posts to the token endpoint, form-encoded. A reply without a new refresh token leaves [keep] valid. */
    private suspend fun exchange(form: Map<String, String>, keep: String?, engine: HttpClientEngine? = null): CloudflareLogin {
        if (engine != null) return client(engine).use { exchange(it, form, keep) }
        return exchange(http, form, keep)
    }

    private suspend fun exchange(http: HttpClient, form: Map<String, String>, keep: String?): CloudflareLogin {
        val res = try {
            http.submitForm(TOKEN, parameters { form.forEach { (k, v) -> append(k, v) } })
        } catch (e: Exception) {
            throw ApiException("Can't reach Cloudflare", cause = e)
        }
        // No cause: what didn't parse quotes the reply, and that holds the tokens.
        val t = runCatching {
            res.body<TokenReply>()
        }.getOrElse { throw ApiException("Cloudflare sent something odd (HTTP ${res.status.value})") }
        if (t.accessToken == null) {
            if (res.status.value == 400 || res.status.value == 401 || t.error == "invalid_grant") throw CloudflareRejected()
            throw ApiException("Cloudflare didn't log you in: ${t.description ?: t.error}")
        }
        return CloudflareLogin(t.accessToken, t.refreshToken ?: keep, now() + t.expiresIn)
    }

    /**
     * Checks that [api] takes its Cloudflare login as the owner's. A REALM_ACCOUNT just set takes a few seconds
     * to reach the worker; one that never takes the login is from before Cloudflare logins.
     */
    suspend fun checkOwner(api: Api): Info {
        repeat(5) { attempt ->
            try {
                val info = api.info()
                if (info.scope != "owner") throw ApiException("not an owner token")
                return info
            } catch (e: ApiException) {
                if (e.message != "bad token") throw e
            }
            if (attempt < 4) delay(2_000)
        }
        throw ApiException("That worker is too old for Cloudflare login. Redeploy it, then log in again.")
    }

    fun now() = Clock.System.now().epochSeconds
}

/**
 * A Cloudflare login as an owner's [Bearer]: its access token, renewed as its hour runs out. [save] keeps
 * every new one (refresh tokens are single-use), and gets null once Cloudflare turns the login down.
 */
class CloudflareSession(login: CloudflareLogin, private val save: (CloudflareLogin?) -> Unit) : Bearer {
    var login = login
        private set
    private val lock = Mutex()

    override suspend fun token(): String = lock.withLock {
        if (Cloudflare.now() < login.expires - 60) return login.access
        try {
            login = Cloudflare.refresh(login)
        } catch (e: CloudflareRejected) {
            save(null)
            throw e
        }
        save(login)
        login.access
    }
}
