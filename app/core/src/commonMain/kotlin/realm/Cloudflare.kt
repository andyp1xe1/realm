package realm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.forms.submitForm
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import io.ktor.util.generateNonceSuspend
import io.ktor.util.sha256
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/** The browser, for logins that come back to localhost. */
interface Browser {
    /** Opens [url] and waits for it to come back to http://localhost:[port][path]; returns its query parameters. */
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

data class Worker(val account: String, val accountName: String, val origin: String)

/** Cloudflare turned the login down: the only way on is logging in again. */
class CloudflareRejected : Exception("Your Cloudflare login has expired. Log in again.")

object Cloudflare {
    private const val CLIENT_ID = "e48e69845742def220449dec54f713b3"
    private const val AUTHORIZE = "https://dash.cloudflare.com/oauth2/auth"
    private const val TOKEN = "https://dash.cloudflare.com/oauth2/token"
    private const val REVOKE = "https://dash.cloudflare.com/oauth2/revoke"
    private const val PORT = 53134
    private const val CALLBACK = "/oauth/callback"
    private const val REDIRECT = "http://localhost:$PORT$CALLBACK"
    const val WORKER = "realm"

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
        val verifier = generateNonceSuspend() + generateNonceSuspend()
        val challenge = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(sha256(verifier.encodeToByteArray()))
        val state = generateNonceSuspend()
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

    /** Refresh tokens are single-use, so the result replaces [login]. */
    suspend fun refresh(login: CloudflareLogin): CloudflareLogin {
        val refresh = login.refresh ?: throw CloudflareRejected()
        return exchange(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to CLIENT_ID), keep = refresh)
            .copy(account = login.account)
    }

    /** Ends the login on Cloudflare's side; best effort. */
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
        val t = runCatching {
            res.body<TokenReply>()
        }.getOrElse { throw ApiException("Cloudflare sent something odd (HTTP ${res.status.value})") }
        if (t.accessToken == null) {
            if (res.status.value == 400 || res.status.value == 401 || t.error == "invalid_grant") throw CloudflareRejected()
            throw ApiException("Cloudflare didn't log you in: ${t.description ?: t.error}")
        }
        return CloudflareLogin(t.accessToken, t.refreshToken ?: keep, now() + t.expiresIn)
    }

    /** Checks that [api] takes its Cloudflare login as the owner's, waiting up to ~8 s for a just-set REALM_ACCOUNT to arrive. */
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
        throw ApiException("That worker doesn't take this Cloudflare login as its owner. Redeploy it, then log in again.")
    }

    fun now() = Clock.System.now().epochSeconds
}

/** A Cloudflare login as an owner's [Bearer], renewed as it runs out; [save] gets every new login, and null once Cloudflare rejects it. */
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
