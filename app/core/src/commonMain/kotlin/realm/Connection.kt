package realm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Strings kept between runs, by key. */
interface Prefs {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

/** A saved connection; [cloudflare] is the Cloudflare login kept for deploying, which for [ByCloudflare] is also the owner's token. */
sealed interface Connection {
    val cloudflare: CloudflareLogin?

    /** What the app keeps as yours: a server you own, or a Cloudflare login to deploy one with. */
    sealed interface Own : Connection

    /** The owner of the server at [server], an origin like https://realm.you.workers.dev. */
    sealed interface Owner : Own {
        val server: String

        /** The id of the realm in use; null is the first one, as the plain /api routes use. */
        val realm: String?
    }

    data class ByToken(
        override val server: String,
        val token: Secret,
        override val realm: String? = null,
        override val cloudflare: CloudflareLogin? = null,
    ) : Owner

    data class ByCloudflare(override val server: String, val login: CloudflareLogin, override val realm: String? = null) : Owner {
        override val cloudflare get() = login
    }

    /** A share link, …/s/<token>; it carries its own access and realm. */
    data class Shared(val url: String, override val cloudflare: CloudflareLogin? = null) : Connection

    /** Logged in with Cloudflare, but connected to nothing yet: there was no realm worker on the account. */
    data class LoginOnly(override val cloudflare: CloudflareLogin) : Own

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        /** Reads the CLI's config.json: null when it holds nothing, an [ApiException] when it's malformed or contradicts itself. */
        fun decode(text: String): Connection? {
            val c = try {
                json.decodeFromString<ConfigJson>(text)
            } catch (e: SerializationException) {
                throw ApiException("The saved connection isn't readable: ${e.message}")
            } catch (e: IllegalArgumentException) {
                throw ApiException("The saved connection isn't readable: ${e.message}")
            }
            val token = c.token?.takeIf { it.value.isNotEmpty() }
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

        /** Writes [connection] as the CLI's config.json. */
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

@Serializable
private data class ConfigJson(
    val url: String = "",
    val token: Secret? = null,
    val realm: String? = null,
    val cloudflare: CloudflareLogin? = null,
)

/** The connection of your own kept by [saveOwn]; null when there's none or it can't be read. */
fun Prefs.own(): Connection.Own? {
    val server = get("server")
    val login = Remembered(this, "cloudflare", CloudflareLogin.serializer()).get()
    if (server == null) return login?.let { Connection.LoginOnly(it) }
    return get("token")?.let { Connection.ByToken(server, Secret(it), cloudflare = login) }
        ?: login?.let { Connection.ByCloudflare(server, it) }
}

/** Keeps [own] for [Prefs.own] to find next time; null forgets it. */
fun Prefs.saveOwn(own: Connection.Own?) {
    set("server", (own as? Connection.Owner)?.server)
    set("token", (own as? Connection.ByToken)?.token?.value)
    Remembered(this, "cloudflare", CloudflareLogin.serializer()).set(own?.cloudflare)
}
