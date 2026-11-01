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

    /** Jo's account, and Studio, which has no realm, as the consent screen gave them, in /accounts' recorded shape. */
    private val twoAccounts = Recorded.ACCOUNTS.replace(
        "\"result\": [",
        "\"result\": [\n    {\"id\": \"a2\", \"name\": \"Studio\", \"type\": \"standard\"},",
    )

    /**
     * Cloudflare as it answered about Jo's account, with or without its realm worker, which answers its owner;
     * with [studio], the login was given Studio's account too.
     */
    private fun cloudflare(worker: Boolean, studio: Boolean = false) = FakeClouds { route, _ ->
        when (route) {
            "POST dash.cloudflare.com/oauth2/token" ->
                json("""{"access_token":"cf-token","refresh_token":"r1","expires_in":3600}""")

            "GET $CF/accounts" -> if (studio) json(twoAccounts) else null

            "GET $CF/accounts/a1/workers/scripts" -> if (worker) null else cf("[]")

            "GET $CF/accounts/a2/workers/scripts" -> cf("[]")

            "GET realm.jo.workers.dev/api/info" -> json(OWNER_INFO)

            "GET realm.jo.workers.dev/api/realms" -> json(TWO_REALMS)

            else -> null
        }
    }

    @Test
    fun noWorkerKeepsTheLoginForItsOneAccount() = runTest {
        val c = cloudflare(worker = false)
        val out = logInWithCloudflare(browser, "test", engine = c.engine)

        val none = assertIs<NoWorker>(out)
        assertEquals("cf-token", none.login.access)
        assertEquals("r1", none.login.refresh)
        assertEquals("a1", none.login.account) // kept with the login, as the CLI keeps it
        assertEquals(ACCOUNT, none.account)
        assertEquals(1, c.routes.count { "oauth2/token" in it }) // one consent, one exchange
    }

    @Test
    fun severalAccountsAreTheUsersToChooseFromEvenWithRealmOnOnlyOne() = runTest {
        val c = cloudflare(worker = true, studio = true)
        val out = assertIs<SeveralAccounts>(logInWithCloudflare(browser, "test", engine = c.engine))

        assertEquals(listOf("Studio", "Jo's account"), out.accounts.map { it.name })
        assertEquals(null, out.login.account)
        assertTrue(c.routes.none { "/workers/" in it || "realm.jo.workers.dev" in it }, c.routes.toString()) // nothing signed in to

        // Each one's state, as the chooser asks it.
        val api = CloudflareApi(Bearer { out.login.access }, c.engine)
        assertEquals(RealmOn.None, api.realmOn(out.accounts[0]))
        assertEquals("https://realm.jo.workers.dev", assertIs<RealmOn.Running>(api.realmOn(out.accounts[1])).worker.origin)

        // Choosing Jo's signs in to it with the same login: no second consent.
        val s = signInOn(out.accounts[1], CloudflareSession(out.login) {}, "test", engine = c.engine)
        assertEquals("a1", assertIs<Connection.ByCloudflare>(s.connection).login.account)
        assertEquals(1, c.routes.count { "oauth2/token" in it })
    }

    @Test
    fun oneWorkerSignsInToIt() = runTest {
        val out = logInWithCloudflare(browser, "test", engine = cloudflare(worker = true).engine)

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
