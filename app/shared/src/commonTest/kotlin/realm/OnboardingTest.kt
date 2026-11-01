package realm

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingTest {
    private class MapPrefs(vararg entries: Pair<String, String>) : Prefs {
        val map = mutableMapOf(*entries)
        override fun get(key: String) = map[key]
        override fun set(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private class TestPlatform(override val prefs: Prefs) : Platform {
        override val name = "test"
        override val incoming = emptyFlow<Incoming>()
        override val isForeground = false
        override val canTakePhoto = false
        override suspend fun pickFiles() = emptyList<LocalFile>()
        override suspend fun takePhoto(): LocalFile? = null
        override suspend fun startRecording(): Recording? = null
        override fun clipboardFiles() = emptyList<LocalFile>()
        override fun copyText(text: String) {}
        override suspend fun save(url: String, auth: String?, name: String) = ""
        override suspend fun open(url: String, auth: String?, name: String) {}
        override fun decodeImage(bytes: ByteArray, maxSize: Int): ImageBitmap? = null
        override fun cachedImage(key: String): ByteArray? = null
        override fun cacheImage(key: String, bytes: ByteArray) {}
        override fun formatTime(epochMs: Long) = ""
        override fun formatDay(epochMs: Long) = ""
        override suspend fun authorize(url: String, port: Int, path: String) = error("no browser in tests")
    }

    /** A login with no refresh token, so nothing renews it or ends it over the network. */
    private val login = CloudflareLogin("cf", null, Cloudflare.now() + 3600)
    private val kept = "cloudflare" to """{"access":"cf","expires":${login.expires},"account":"a1"}"""

    /** The same login, kept before an account was chosen. */
    private val choosing = "cloudflare" to """{"access":"cf","expires":${login.expires}}"""

    /**
     * Jo's account with R2, a workers.dev subdomain and room for a database, but no realm worker until it's
     * uploaded; the upload fails while [uploads] says so. With [studio], the login has Studio's account too,
     * where realm runs at realm.studio.workers.dev. GitHub has release v1.2.0.
     */
    private class Cloud(private val scope: TestScope, var uploads: Boolean = true, val studio: Boolean = false) {
        /** On the test's clock, when realm.jo.workers.dev starts to answer: a new address takes minutes. */
        var answersAt = 0L

        var worker = false
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
                "GET api.github.com/repos/andyp1xe1/realm/releases" -> json(
                    """[{"tag_name":"v1.2.0","assets":[{"name":"worker.json","browser_download_url":"https://$gh/worker.json"},
                        {"name":"worker.js","browser_download_url":"https://$gh/worker.js"}]}]""",
                )

                "GET $gh/worker.json" -> json(
                    """{"version":3,"name":"realm","main_module":"worker.js","compatibility_date":"2026-10-01",
                        "sha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                        "r2_buckets":[{"binding":"BUCKET","bucket_name":"realm"}],"d1_databases":[{"binding":"DB","database_name":"realm"}]}""",
                )

                "GET $gh/worker.js" -> respond("abc")

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

                "GET realm.studio.workers.dev/api/info" -> json("""{"version":3,"scope":"owner"}""")

                "GET realm.studio.workers.dev/api/realms" -> json("""{"realms":[{"id":"main","name":"Studio's","created":1,"count":0}]}""")

                "GET cf/accounts/a1/workers/scripts" -> cf(if (worker) """[{"id":"realm"}]""" else "[]")

                "GET cf/accounts/a1/workers/subdomain" -> cf("""{"subdomain":"jo"}""")

                "GET cf/accounts/a1/r2/buckets" -> cf("""{"buckets":[]}""")

                "GET cf/accounts/a1/d1/database", "GET cf/zones", "GET cf/accounts/a1/workers/domains" -> cf("[]")

                "POST cf/accounts/a1/d1/database" -> cf("""{"uuid":"db1","name":"realm"}""")

                "PUT cf/accounts/a1/workers/scripts/realm" ->
                    if (uploads) {
                        worker = true
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
                        throw IllegalStateException("Network is unreachable") // its certificate isn't there yet
                    } else if (worker) {
                        json("""{"version":3,"scope":"owner"}""")
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

    private fun TestScope.home(prefs: MapPrefs, cloud: Cloud) = Home(TestPlatform(prefs), this, cloud.engine)

    @Test
    fun aRestartWithOnlyACloudflareLoginOpensOnboardingWithItsChecks() = runTest {
        val home = home(MapPrefs(kept), Cloud(this))
        assertEquals(Screen.Onboarding, home.screen)
        advanceUntilIdle()

        val o = home.onboarding
        assertEquals("a1", o.account?.id) // the account it was kept for
        val r = assertNotNull(o.requirements.state.value)
        assertEquals(R2Check.On, r.r2)
        assertNotNull(o.deployment)
    }

    @Test
    fun aFailedDeployKeepsTheLoginAndRetryingSignsIn() = runTest {
        val prefs = MapPrefs(kept)
        val cloud = Cloud(this, uploads = false)
        val home = home(prefs, cloud)
        advanceUntilIdle()

        home.onboarding.deploy()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, home.screen)
        assertTrue(home.onboarding.deployFailed)
        assertEquals("Upload failed", home.onboarding.problem)
        assertIs<Connection.LoginOnly>(prefs.own()) // still logged in, next run too
        assertEquals(Screen.Onboarding, home(MapPrefs(*prefs.map.toList().toTypedArray()), cloud).screen)

        cloud.uploads = true
        home.onboarding.deploy()
        advanceUntilIdle()
        assertEquals(Screen.Tabs, home.screen)
        assertEquals(listOf("Main"), home.realms.map { it.name })
        val owner = assertIs<Connection.ByCloudflare>(prefs.own())
        assertEquals("https://realm.jo.workers.dev", owner.server)
        assertEquals("a1", owner.login.account)
        assertNull(home.onboarding.session)
    }

    @Test
    fun signingOutEndsTheLogin() = runTest {
        val prefs = MapPrefs(kept)
        val home = home(prefs, Cloud(this))
        advanceUntilIdle()

        home.signOut()
        assertEquals(Screen.Setup, home.screen)
        assertNull(prefs.own())
        assertEquals(Screen.Setup, home(MapPrefs(*prefs.map.toList().toTypedArray()), Cloud(this)).screen)
    }

    @Test
    fun severalAccountsAreChosenFromAndTheChoiceIsKept() = runTest {
        val prefs = MapPrefs(choosing)
        val cloud = Cloud(this, studio = true)
        val home = home(prefs, cloud)
        advanceUntilIdle()

        val o = home.onboarding
        assertNull(o.account) // choosing
        assertEquals(RealmOn.None, o.realms.getValue("a1").state.value)
        assertIs<RealmOn.Running>(o.realms.getValue("a2").state.value)

        // No realm on Jo's: its requirements, kept for it next run.
        o.choose(o.accounts.state.value!!.first { it.id == "a1" })
        advanceUntilIdle()
        assertNotNull(o.deployment)
        assertEquals("a1", assertIs<Connection.LoginOnly>(prefs.own()).cloudflare.account)
        val again = home(MapPrefs(*prefs.map.toList().toTypedArray()), cloud)
        advanceUntilIdle()
        assertEquals("a1", again.onboarding.account?.id)

        // Back to the chooser, then Studio's, where realm runs: signed in to it, offered first next time.
        o.pickAnother()
        advanceUntilIdle()
        assertNull(o.account)
        o.choose(o.accounts.state.value!!.first { it.id == "a2" })
        advanceUntilIdle()
        assertEquals(Screen.Tabs, home.screen)
        assertEquals("https://realm.studio.workers.dev", assertIs<Connection.ByCloudflare>(prefs.own()).server)
        assertEquals(listOf("a2", "a1"), o.ordered(listOf(Account("a1"), Account("a2"))).map { it.id })
    }

    @Test
    fun anotherAccountFromSettingsKeepsTheLoginAndOpensTheChooser() = runTest {
        val prefs = MapPrefs(choosing)
        val home = home(prefs, Cloud(this, studio = true))
        advanceUntilIdle()
        home.onboarding.choose(Account("a2", "Studio"))
        advanceUntilIdle()
        assertEquals(Screen.Tabs, home.screen)

        home.useAnotherAccount()
        assertEquals(Screen.Onboarding, home.screen)
        assertEquals("cf", home.onboarding.session?.login?.access) // the same login: no new consent
        assertNull(assertIs<Connection.LoginOnly>(prefs.own()).cloudflare.account)
        advanceUntilIdle()
        assertEquals(2, home.onboarding.realms.size)
    }

    @Test
    fun aDeployWhoseNewAddressTakesMinutesStaysLoggedInAndSignsInOnceItAnswers() = runTest {
        val prefs = MapPrefs(kept)
        val cloud = Cloud(this).apply { answersAt = 4 * 60_000L }
        val home = home(prefs, cloud)
        advanceUntilIdle()

        home.onboarding.deploy()
        advanceTimeBy(2 * 60_000L) // past the deploy's own minute of waiting
        assertEquals(Screen.Onboarding, home.screen)
        assertEquals("https://realm.jo.workers.dev", home.onboarding.comingUp)
        assertNull(home.onboarding.problem)
        assertIs<Connection.LoginOnly>(prefs.own()) // still logged in; the next run finds the worker to sign in to

        advanceTimeBy(3 * 60_000L)
        runCurrent()
        assertEquals(Screen.Tabs, home.screen)
        assertEquals(listOf("Main"), home.realms.map { it.name })
        assertIs<Connection.ByCloudflare>(prefs.own())
    }
}
