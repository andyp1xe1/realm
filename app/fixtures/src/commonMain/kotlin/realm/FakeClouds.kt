package realm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One request as it reached the fake: its route, like PUT api.cloudflare.com/client/v4/accounts/a1, URL and body. */
class Seen(val route: String, val url: Url, val headers: Map<String, String?>, val body: ByteArray) {
    val text get() = body.decodeToString()
}

/**
 * Stand-ins for Cloudflare, GitHub and a deployed worker at once, routed by "METHOD host/path". Reads a test
 * leaves alone get Cloudflare's [Recorded] answers about Jo's account and GitHub's [RecordedGitHub] release
 * list; any other route nobody answers is a Cloudflare 404, so a test sees the call it didn't expect.
 */
class FakeClouds(private val answer: suspend MockRequestHandleScope.(route: String, seen: Seen) -> HttpResponseData?) {
    val seen = mutableListOf<Seen>()
    val engine = MockEngine { req ->
        val s = Seen(
            "${req.method.value} ${req.url.host}${req.url.encodedPath}",
            req.url,
            mapOf(HttpHeaders.Authorization to req.headers[HttpHeaders.Authorization]),
            req.body.toByteArray(),
        )
        seen += s
        answer(s.route, s) ?: recorded(s.route)?.let { json(it) } ?: cfError(HttpStatusCode.NotFound, 7003, "No route for ${s.route}")
    }

    /** The routes called, in order, without the Cloudflare API's prefix. */
    val routes get() = seen.map { it.route.replace("api.cloudflare.com/client/v4", "cf") }

    fun api() = CloudflareApi(Bearer { "cf-token" }, engine)
}

/** What Cloudflare answers to [route], a read on Jo's account, as it really answered; null for any other. */
fun recorded(route: String): String? = when (route) {
    "GET $CF/accounts" -> Recorded.ACCOUNTS
    "GET $CF/accounts/a1/workers/scripts" -> Recorded.SCRIPTS
    "GET $CF/accounts/a1/workers/domains" -> Recorded.DOMAINS
    "GET $CF/accounts/a1/workers/subdomain" -> Recorded.SUBDOMAIN
    "GET $CF/accounts/a1/workers/scripts/realm/subdomain" -> Recorded.SCRIPT_SUBDOMAIN
    "GET $CF/accounts/a1/workers/scripts/realm/secrets" -> Recorded.SECRETS
    "GET $CF/accounts/a1/r2/buckets" -> Recorded.BUCKETS
    "GET $CF/accounts/a1/d1/database" -> Recorded.D1
    "GET $CF/zones" -> Recorded.ZONES
    "GET api.github.com/repos/andyp1xe1/realm/releases" -> RecordedGitHub.RELEASES
    else -> null
}

/**
 * The [recorded] reply with its result changed by [change], everything else in its envelope as Cloudflare
 * sent it: an account in another state, answered in the shape the real one came in.
 */
fun reshaped(recorded: String, change: (result: JsonElement) -> JsonElement): String {
    val reply = Json.parseToJsonElement(recorded).jsonObject
    return JsonObject(reply + ("result" to change(reply.getValue("result")))).toString()
}

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

/** A Cloudflare success with [result], in the envelope most of its endpoints send ([Recorded.SUBDOMAIN]). */
fun MockRequestHandleScope.cf(result: String) = json("""{"result":$result,"success":true,"errors":[],"messages":[]}""")

/** A Cloudflare refusal, in the envelope it really sends them in ([Recorded.NO_SUCH_WORKER]). */
fun MockRequestHandleScope.cfError(status: HttpStatusCode, code: Int, message: String) = json(
    """{"result":null,"success":false,"errors":[{"code":$code,"message":"$message"}],"messages":[]}""",
    status,
)

const val CF = "api.cloudflare.com/client/v4"
