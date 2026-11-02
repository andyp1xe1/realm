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

/** One request as it reached the fake: its route, like PUT api.cloudflare.com/client/v4/accounts/a1, URL and body. */
class Seen(val route: String, val url: Url, val headers: Map<String, String?>, val body: ByteArray) {
    val text get() = body.decodeToString()
}

/**
 * Stand-ins for Cloudflare, GitHub and a deployed worker at once, routed by "METHOD host/path". Reads a test
 * leaves alone get Cloudflare's [Recorded] answers about Jo's account; any other route nobody answers is a
 * Cloudflare 404, so a test sees the call it didn't expect.
 */
class FakeClouds(private val answer: MockRequestHandleScope.(route: String, seen: Seen) -> HttpResponseData?) {
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

/** What Cloudflare reads on Jo's account answer, as it really answered them. */
private fun recorded(route: String): String? = when (route) {
    "GET $CF/accounts" -> Recorded.ACCOUNTS
    "GET $CF/accounts/a1/workers/scripts" -> Recorded.SCRIPTS
    "GET $CF/accounts/a1/workers/domains" -> Recorded.DOMAINS
    "GET $CF/accounts/a1/workers/subdomain" -> Recorded.SUBDOMAIN
    "GET $CF/accounts/a1/workers/scripts/realm/subdomain" -> Recorded.SCRIPT_SUBDOMAIN
    "GET $CF/accounts/a1/workers/scripts/realm/secrets" -> Recorded.SECRETS
    "GET $CF/accounts/a1/r2/buckets" -> Recorded.BUCKETS
    "GET $CF/accounts/a1/d1/database" -> Recorded.D1
    "GET $CF/zones" -> Recorded.ZONES
    else -> null
}

/** A Cloudflare success with [result], in the envelope most of its endpoints send ([Recorded.SUBDOMAIN]). */
fun MockRequestHandleScope.cf(result: String) = respond(
    """{"result":$result,"success":true,"errors":[],"messages":[]}""",
    HttpStatusCode.OK,
    headersOf(HttpHeaders.ContentType, "application/json"),
)

/** A Cloudflare refusal, in the envelope it really sends them in ([Recorded.NO_SUCH_WORKER]). */
fun MockRequestHandleScope.cfError(status: HttpStatusCode, code: Int, message: String) = respond(
    """{"result":null,"success":false,"errors":[{"code":$code,"message":"$message"}],"messages":[]}""",
    status,
    headersOf(HttpHeaders.ContentType, "application/json"),
)

const val CF = "api.cloudflare.com/client/v4"
val ACCOUNT = Account("a1", "Jo's account")

/** The worker as the release CI builds it; sha256 of "abc", as FIPS 180-2 gives it. */
val MANIFEST = Manifest(
    version = 3,
    name = "realm",
    mainModule = "worker.js",
    sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    compatibilityDate = "2026-10-01",
    buckets = listOf(BucketBinding("BUCKET", "realm")),
    databases = listOf(DatabaseBinding("DB", "realm")),
    crons = listOf("17 4 * * *"),
)
val RELEASE = Release(Semver(1, 0, 0), MANIFEST, "abc".encodeToByteArray(), null)

fun found(
    script: Existing? = null,
    buckets: Set<String> = emptySet(),
    databases: Set<String> = emptySet(),
    subdomain: String? = "jo",
    workersDevOn: Boolean = false,
    domains: List<Domain> = emptyList(),
) = Found(ACCOUNT, script, buckets, databases, true, subdomain, workersDevOn, emptyList(), domains)
