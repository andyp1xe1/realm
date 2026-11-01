// What settings shows about the server you're signed in to, fetched in one go. It's all serializable, so the
// next run can show the last of it at once, from a Remembered, while it asks again.
package realm

import io.ktor.client.engine.HttpClientEngine
import kotlinx.serialization.Serializable

@Serializable
data class ServerFacts(
    /** The address the app talks to, an origin like https://realm.jo.workers.dev. */
    val origin: String,
    val info: Info,
    /** Where the worker answers, as its Cloudflare login sees it; null signed in with a fixed token, with no login to ask. */
    val addresses: Addresses?,
)

/** Asks [server], and [session]'s account if there's one, for [ServerFacts]. */
suspend fun serverFacts(server: Api, session: CloudflareSession?, engine: HttpClientEngine?): ServerFacts {
    val info = server.info()
    val addresses = session?.login?.account?.let { id -> CloudflareApi(session, engine).use { cf -> cf.addresses(cf.account(id)) } }
    return ServerFacts(server.base.removeSuffix("/api"), info, addresses)
}
