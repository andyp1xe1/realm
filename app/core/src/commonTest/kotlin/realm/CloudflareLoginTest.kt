package realm

import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CloudflareLoginTest {
    /** A browser that consents at once: it comes back with a code and the state it was sent with. */
    private val browser = object : Browser {
        override suspend fun authorize(url: String, port: Int, path: String) =
            mapOf("code" to "c1", "state" to Url(url).parameters["state"]!!)
    }

    /** Cloudflare with accounts a1 and a2, the realm worker on [withWorker] of them, answering its owner. */
    private fun cloudflare(vararg withWorker: String) = FakeClouds { route, _ ->
        when {
            route == "POST dash.cloudflare.com/oauth2/token" ->
                json("""{"access_token":"cf-token","refresh_token":"r1","expires_in":3600}""")

            route == "GET $CF/accounts" -> cf("""[{"id":"a1","name":"Jo"},{"id":"a2","name":"Work"}]""")

            route.endsWith("/workers/scripts") ->
                cf(if (route.split('/')[4] in withWorker) """[{"id":"realm"}]""" else "[]")

            // As Cloudflare really answers this one: null errors and messages, unlike its other endpoints.
            route.endsWith("/workers/domains") ->
                json(
                    """{"result":[],"success":true,"errors":null,"messages":null,""" +
                        """"result_info":{"page":1,"per_page":0,"count":0,"total_count":0}}""",
                )

            route.endsWith("/scripts/realm/subdomain") -> cf("""{"enabled":true}""")

            route.endsWith("/workers/subdomain") -> cf("""{"subdomain":"jo"}""")

            route.endsWith("/scripts/realm/secrets") -> cf("""[{"name":"REALM_ACCOUNT"}]""")

            route == "GET realm.jo.workers.dev/api/info" -> json(OWNER_INFO)

            route == "GET realm.jo.workers.dev/api/realms" -> json(TWO_REALMS)

            else -> null
        }
    }

    @Test
    fun noWorkerKeepsTheLoginAndTheAccountsToDeployTo() = runTest {
        val c = cloudflare()
        val out = logInWithCloudflare(browser, "test", choose = { error("nothing to choose") }, engine = c.engine)

        val none = assertIs<NoWorker>(out)
        assertEquals("cf-token", none.login.access)
        assertEquals("r1", none.login.refresh)
        assertEquals(listOf("Jo", "Work"), none.accounts.map { it.name })
        assertEquals(1, c.routes.count { "oauth2/token" in it }) // one consent, one exchange
    }

    @Test
    fun oneWorkerSignsInToIt() = runTest {
        val out = logInWithCloudflare(browser, "test", choose = { error("nothing to choose") }, engine = cloudflare("a1").engine)

        val s = assertIs<SignedIn>(out)
        assertEquals(listOf("Main", "Lisbon trip"), s.realms.map { it.name })
        val c = assertIs<Connection.ByCloudflare>(s.connection)
        assertEquals("https://realm.jo.workers.dev", c.server)
        assertEquals("a1", c.login.account)
    }

    @Test
    fun aWorkerJustDeployedIsSignedInToWithTheSameLogin() = runTest {
        val c = cloudflare("a1")
        val login = CloudflareLogin("cf-token", "r1", Cloudflare.now() + 3600)
        val s = signInWithCloudflare("https://realm.jo.workers.dev", login.copy(account = "a1"), "test", engine = c.engine)

        assertEquals(2, s.realms.size)
        assertTrue(c.seen.all { it.headers["Authorization"] == "Bearer cf-token" })
    }
}
