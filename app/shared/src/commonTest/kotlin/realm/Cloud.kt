package realm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Jo's account with R2, a workers.dev subdomain and room for a database, but no realm worker, nor its bucket
 * and database, until it's uploaded; the upload fails while [uploads] says so. With [studio], the login has Studio's account too,
 * where realm runs at realm.studio.workers.dev. GitHub has release v1.2.0.
 */
internal class Cloud(private val scope: TestScope, var uploads: Boolean = true, val studio: Boolean = false) {
    /** On the test's clock, when realm.jo.workers.dev starts to answer: a new address takes minutes. */
    var answersAt = 0L

    var worker = false

    /** The release the worker says it runs; an upload makes it v1.2.0. */
    var build: String? = null

    /** Whether the worker turns this login down as its owner's, as one bound to another account would. */
    var refuses = false

    var limitedUntil: Long? = null

    var newApp: String? = null
    val gh = "github.com/andyp1xe1/realm/releases/download/v1.2.0"

    // On the test's clock, so advancing it runs every request to the end.
    val engine =
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(scope.testScheduler)
            }.also { it.addHandler { req -> answer(req) } },
        )

    private fun MockRequestHandleScope.answer(req: HttpRequestData): HttpResponseData {
        val route = "${req.method.value} ${req.url.host}${req.url.encodedPath}".replace("api.cloudflare.com/client/v4", "cf")
        return when (route) {
            "GET github.com/andyp1xe1/realm/releases/latest/download/releases.json" -> limitedUntil?.let {
                respond(
                    """{"message":"rate limited"}""",
                    HttpStatusCode.TooManyRequests,
                    headersOf("x-ratelimit-remaining" to listOf("0"), "x-ratelimit-reset" to listOf("$it")),
                )
            } ?: json(
                "[" + newApp?.let { """{"tag_name":"$it","assets":[]},""" }.orEmpty() +
                    """{"tag_name":"v1.2.0","assets":[{"name":"worker.json","browser_download_url":"https://$gh/worker.json"},
                    {"name":"worker.js","browser_download_url":"https://$gh/worker.js"}]}]""",
            )

            "GET $gh/worker.json" -> json(
                """{"version":${Api.VERSION},"name":"realm","main_module":"worker.js","compatibility_date":"2026-10-01",
                    "sha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    "r2_buckets":[{"binding":"BUCKET","bucket_name":"realm"}],"d1_databases":[{"binding":"DB","database_name":"realm"}]}""",
            )

            "GET $gh/worker.js" -> respond("abc")

            "POST dash.cloudflare.com/oauth2/token" -> json("""{"access_token":"cf","expires_in":3600}""")

            "GET cf/accounts" ->
                cf(
                    if (studio) {
                        """[{"id":"a1","name":"Jo's account"},{"id":"a2","name":"Studio"}]"""
                    } else {
                        """[{"id":"a1","name":"Jo's account"}]"""
                    },
                )

            "GET cf/accounts/a2/workers/scripts" -> cf("""[{"id":"realm"}]""")

            "GET cf/accounts/a2/workers/domains" -> cf("[]")

            "GET cf/accounts/a2/workers/subdomain" -> cf("""{"subdomain":"studio"}""")

            "GET cf/accounts/a2/workers/scripts/realm/subdomain" -> cf("""{"enabled":true}""")

            "GET cf/accounts/a2/workers/scripts/realm/secrets" -> cf("""[{"name":"REALM_ACCOUNT"}]""")

            "GET realm.studio.workers.dev/api/info" -> json("""{"version":${Api.VERSION},"scope":"owner"}""")

            "GET realm.studio.workers.dev/api/realms" -> json("""{"realms":[{"id":"main","name":"Studio's","created":1,"count":0}]}""")

            "GET cf/accounts/a1/workers/scripts" -> cf(if (worker) """[{"id":"realm"}]""" else "[]")

            "GET cf/accounts/a1/workers/subdomain" -> cf("""{"subdomain":"jo"}""")

            "GET cf/accounts/a1/r2/buckets" -> cf(if (worker) """{"buckets":[{"name":"realm"}]}""" else """{"buckets":[]}""")

            "GET cf/accounts/a1/d1/database" -> cf(if (worker) """[{"uuid":"db1","name":"realm"}]""" else "[]")

            "GET cf/zones", "GET cf/accounts/a1/workers/domains" -> cf("[]")

            "POST cf/accounts/a1/d1/database" -> cf("""{"uuid":"db1","name":"realm"}""")

            "PUT cf/accounts/a1/workers/scripts/realm" ->
                if (uploads) {
                    worker = true
                    build = "v1.2.0"
                    cf("""{"id":"realm"}""")
                } else {
                    json(
                        """{"success":false,"errors":[{"code":10000,"message":"Upload failed"}]}""",
                        HttpStatusCode.InternalServerError,
                    )
                }

            "GET cf/accounts/a1/workers/scripts/realm/subdomain" -> cf("""{"enabled":$worker}""")

            "GET realm.jo.workers.dev/api/info" ->
                if (scope.testScheduler.currentTime < answersAt) {
                    throw IOException("Network is unreachable") // its certificate isn't there yet
                } else if (refuses) {
                    json("""{"error":"bad token"}""", HttpStatusCode.Unauthorized)
                } else if (worker) {
                    json(Json.encodeToString(Info.serializer(), Info(version = Api.VERSION, scope = "owner", build = build)))
                } else {
                    json("""{"error":"not found"}""", HttpStatusCode.NotFound)
                }

            "GET realm.jo.workers.dev/api/realms" -> json("""{"realms":[{"id":"main","name":"Main","created":1,"count":0}]}""")

            else -> if (route.contains(" cf/")) cf("{}") else json("""{"items":[],"next":null}""")
        }
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.cf(result: String) = json("""{"success":true,"errors":[],"result":$result}""")
}
