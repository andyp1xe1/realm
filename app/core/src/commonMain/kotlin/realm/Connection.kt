// What's saved between runs: where you're connected, in the CLI's config.json shape so either can read it.
package realm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Strings kept between runs, by key. Implemented by each app's Platform. */
interface Prefs {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

/**
 * A saved connection: your own server's owner, by token or by Cloudflare login, or a share link. Every kind
 * has [cloudflare], the Cloudflare login kept for deploying, if there is one; for [ByCloudflare] it's the
 * login that is the owner's token.
 */
sealed interface Connection {
    val cloudflare: CloudflareLogin?

    /** The owner of the server at [server], an origin like https://realm.you.workers.dev. */
    sealed interface Owner : Connection {
        val server: String

        /** The id of the realm in use; null is the first one, as the plain /api routes use. */
        val realm: String?
    }

    data class ByToken(
        override val server: String,
        val token: String,
        override val realm: String? = null,
        override val cloudflare: CloudflareLogin? = null,
    ) : Owner

    data class ByCloudflare(override val server: String, val login: CloudflareLogin, override val realm: String? = null) : Owner {
        override val cloudflare get() = login
    }

    /** A share link, …/s/<token>; it carries its own access and realm. */
    data class Shared(val url: String, override val cloudflare: CloudflareLogin? = null) : Connection

    /** Logged in with Cloudflare, but connected to nothing yet: there was no realm worker on the account. */
    data class LoginOnly(override val cloudflare: CloudflareLogin) : Connection

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        /**
         * Reads the CLI's config.json. Null when it holds nothing; throws an [ApiException] when it's malformed
         * or says something that can't be, like a share link with an owner token.
         */
        fun decode(text: String): Connection? {
            val c = try {
                json.decodeFromString<ConfigJson>(text)
            } catch (e: SerializationException) {
                throw ApiException("The saved connection isn't readable: ${e.message}")
            } catch (e: IllegalArgumentException) {
                throw ApiException("The saved connection isn't readable: ${e.message}")
            }
            val token = c.token?.ifEmpty { null }
            val realm = c.realm?.ifEmpty { null }
            val url = c.url.trim()
            fun bad(why: String): Nothing = throw ApiException("The saved connection $why")
            return when {
                url.isEmpty() -> when {
                    token != null || realm != null -> bad("has a token or realm but no server")
                    c.cloudflare != null -> LoginOnly(c.cloudflare)
                    else -> null
                }

                isLink(url) -> when {
                    token != null || realm != null -> bad("is a share link, which takes no token or realm")
                    else -> Shared(linkUrl(url) ?: bad("has a link that isn't one"), c.cloudflare)
                }

                token != null -> ByToken(serverOrigin(url), token, realm, c.cloudflare)

                c.cloudflare != null -> ByCloudflare(serverOrigin(url), c.cloudflare, realm)

                else -> bad("has no owner token or Cloudflare login for $url")
            }
        }

        /** Writes [connection] as the CLI's config.json, indented as the CLI writes it. */
        fun encode(connection: Connection): String = json.encodeToString(
            when (connection) {
                is ByToken -> ConfigJson(connection.server, connection.token, connection.realm, connection.cloudflare)
                is ByCloudflare -> ConfigJson(connection.server, null, connection.realm, connection.login)
                is Shared -> ConfigJson(connection.url, cloudflare = connection.cloudflare)
                is LoginOnly -> ConfigJson("", cloudflare = connection.cloudflare)
            },
        )
    }
}

/** config.json as the CLI writes it: url always, the rest only when set. */
@Serializable
private data class ConfigJson(
    val url: String = "",
    val token: String? = null,
    val realm: String? = null,
    val cloudflare: CloudflareLogin? = null,
)

/**
 * The owner connection the app keeps, under the prefs keys it has always used: "server" and either "token"
 * or "cloudflare". Null when there's none, or what's there can't be read.
 */
fun Prefs.owner(): Connection.Owner? {
    val server = get("server") ?: return null
    val login = Remembered(this, "cloudflare", CloudflareLogin.serializer()).get()
    return get("token")?.let { Connection.ByToken(server, it, cloudflare = login) }
        ?: login?.let { Connection.ByCloudflare(server, it) }
}

/** Keeps [owner] for [Prefs.owner] to find next time; null forgets it. */
fun Prefs.saveOwner(owner: Connection.Owner?) {
    set("server", owner?.server)
    set("token", (owner as? Connection.ByToken)?.token)
    Remembered(this, "cloudflare", CloudflareLogin.serializer()).set(owner?.cloudflare)
}
