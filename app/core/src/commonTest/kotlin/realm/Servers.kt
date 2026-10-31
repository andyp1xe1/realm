package realm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/** A stand-in server: answers each request by its path, and keeps the requests for a test to look at. */
class FakeServer(private val routes: MockRequestHandleScope.(path: String) -> HttpResponseData) {
    val requests = mutableListOf<HttpRequestData>()
    val engine = MockEngine { req ->
        requests += req
        routes(req.url.encodedPath)
    }
}

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

/** What a realm worker says about a route it doesn't have. */
fun MockRequestHandleScope.notFound() = json("""{"error":"not found"}""", HttpStatusCode.NotFound)

/** A bare 404, as a server that isn't realm's answers. */
fun MockRequestHandleScope.bare404() = respond("", HttpStatusCode.NotFound)

const val OWNER_INFO = """{"version":3,"scope":"owner","realm":null}"""
const val TWO_REALMS =
    """{"realms":[{"id":"main","name":"Main","created":1,"count":4},{"id":"trip","name":"Lisbon trip","created":2,"count":0}]}"""
