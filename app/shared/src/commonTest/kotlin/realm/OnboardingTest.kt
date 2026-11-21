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

class OnboardingTest {
    /** A login with no refresh token, so nothing renews it or ends it over the network. */
    private val login = CloudflareLogin(Secret("cf"), null, Cloudflare.now() + 3600)
    private val kept = "cloudflare" to """{"access":"cf","expires":${login.expires},"account":"a1"}"""

    /** The same login, kept before an account was chosen. */
    private val choosing = "cloudflare" to """{"access":"cf","expires":${login.expires}}"""

    private fun TestScope.home(prefs: MapPrefs, cloud: Cloud) = Home(TestPlatform(prefs), this, cloud.engine)

    /** Runs a Cloudflare login to its end: its nonces come from a dispatcher of their own. */
    private suspend fun TestScope.loggedIn(home: Home) {
        advanceUntilIdle()
        while (home.cloudflare.login != null) {
            advanceUntilIdle()
            withContext(Dispatchers.Default) { delay(5) }
        }
        advanceUntilIdle()
    }

    @Test
    fun managingAServerSignedInToWithATokenKeepsTheTokenUntilTheLoginRunsIt() = runTest {
        val prefs = MapPrefs().apply { saveOwn(Connection.ByToken("https://realm.jo.workers.dev", Secret("token"))) }
        val cloud = Cloud(this)
        val home = home(prefs, cloud)
        advanceUntilIdle()

        home.cloudflare.manage()
        loggedIn(home)
        assertIs<Connection.ByToken>(prefs.own())
        assertEquals("https://realm.jo.workers.dev/api", home.server?.base)
        assertEquals(NotThisServer("realm.jo.workers.dev").message, home.message)

        cloud.worker = true
        home.cloudflare.manage()
        loggedIn(home)
        val owner = assertIs<Connection.ByCloudflare>(prefs.own())
        assertEquals("https://realm.jo.workers.dev", owner.server)
        assertEquals("a1", owner.login.account)
        assertEquals(listOf("Main"), home.realms.map { it.name })
        assertNotNull(home.serverSettings.facts?.state?.value?.addresses)
    }

    @Test
    fun loggingInToSeveralAccountsSignsOutOfTheServerSignedInToWithAToken() = runTest {
        val prefs = MapPrefs().apply { saveOwn(Connection.ByToken("https://realm.jo.workers.dev", Secret("token"))) }
        val cloud = Cloud(this, studio = true).apply { worker = true }
        val home = home(prefs, cloud)
        advanceUntilIdle()
        assertEquals(listOf("Main"), home.realms.map { it.name })

        home.cloudflare.logIn()
        loggedIn(home)
        assertEquals(Screen.Onboarding, home.screen)
        assertNull(home.server)
        assertEquals(emptyList(), home.realms.toList())
        assertIs<Connection.LoginOnly>(prefs.own())
    }

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
    fun aRestartNamesTheKeptAccountOnceTheAccountsAreKnown() = runTest {
        val home = home(MapPrefs(kept), Cloud(this))
        assertEquals(Account("a1"), home.onboarding.account)
        advanceUntilIdle()

        assertEquals(Account("a1", "Jo's account"), home.onboarding.account)
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
    fun goingOnWithoutAnAccountIsKept() = runTest {
        val prefs = MapPrefs()
        home(prefs, Cloud(this)).continueWithoutAccount()
        val home = home(prefs, Cloud(this))
        assertEquals(Screen.Tabs, home.screen)
        home.signOut()
        assertEquals(Screen.Setup, home(prefs, Cloud(this)).screen)
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
        assertEquals(Secret("cf"), home.onboarding.session?.login?.access) // the same login: no new consent
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

    @Test
    fun aDeployedWorkerThatRefusesTheLoginSaysSoAtOnce() = runTest {
        val prefs = MapPrefs(kept)
        val cloud = Cloud(this).apply { refuses = true }
        val home = home(prefs, cloud)
        advanceUntilIdle()

        home.onboarding.deploy()
        advanceTimeBy(2 * 60_000L) // the deploy's minute of waiting, then one sign-in try
        assertEquals(Screen.Onboarding, home.screen)
        assertNull(home.onboarding.comingUp)
        assertEquals(NotOwner().message, home.onboarding.problem)
        assertIs<Connection.LoginOnly>(prefs.own())
    }
}
