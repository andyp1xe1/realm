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

    /** Cloudflare as it answered about Jo's account, with or without its realm worker, which answers its owner. */
    private fun cloudflare(worker: Boolean) = FakeClouds { route, _ ->
        when (route) {
            "POST dash.cloudflare.com/oauth2/token" ->
                json("""{"access_token":"cf-token","refresh_token":"r1","expires_in":3600}""")

            "GET $CF/accounts/a1/workers/scripts" -> if (worker) null else cf("[]")

            "GET realm.jo.workers.dev/api/info" -> json(OWNER_INFO)

            "GET realm.jo.workers.dev/api/realms" -> json(TWO_REALMS)

            else -> null
        }
    }

    @Test
    fun noWorkerKeepsTheLoginAndTheAccountsToDeployTo() = runTest {
        val c = cloudflare(worker = false)
        val out = logInWithCloudflare(browser, "test", choose = { error("nothing to choose") }, engine = c.engine)

        val none = assertIs<NoWorker>(out)
        assertEquals("cf-token", none.login.access)
        assertEquals("r1", none.login.refresh)
        assertEquals(listOf(ACCOUNT), none.accounts)
        assertEquals(1, c.routes.count { "oauth2/token" in it }) // one consent, one exchange
    }

    @Test
    fun oneWorkerSignsInToIt() = runTest {
        val out = logInWithCloudflare(browser, "test", choose = { error("nothing to choose") }, engine = cloudflare(worker = true).engine)

        val s = assertIs<SignedIn>(out)
        assertEquals(listOf("Main", "Lisbon trip"), s.realms.map { it.name })
        val c = assertIs<Connection.ByCloudflare>(s.connection)
        assertEquals("https://realm.jo.workers.dev", c.server)
        assertEquals("a1", c.login.account)
    }

    @Test
    fun aWorkerJustDeployedIsSignedInToWithTheSameLogin() = runTest {
        val c = cloudflare(worker = true)
        val login = CloudflareLogin("cf-token", "r1", Cloudflare.now() + 3600)
        val s = signInWithCloudflare("https://realm.jo.workers.dev", login.copy(account = "a1"), "test", engine = c.engine)

        assertEquals(2, s.realms.size)
        assertTrue(c.seen.all { it.headers["Authorization"] == "Bearer cf-token" })
    }
}
