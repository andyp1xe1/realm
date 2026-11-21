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

class UpdatingTest {
    private val login = CloudflareLogin(Secret("cf"), null, Cloudflare.now() + 3600)

    private fun TestScope.home(prefs: MapPrefs, cloud: Cloud) = Home(TestPlatform(prefs), this, cloud.engine)

    private class Installs(installed: String, private val out: Semver, var released: Boolean = true) : Updater {
        override val version = AppVersion.parse(installed)!!
        var installed = 0
        var looking: () -> Unit = {}

        override suspend fun newer(releases: Releases): AppUpdate? {
            looking()
            return AppUpdate(out, "https://x/realm-$out.apk", "ab", Api.VERSION).takeIf { released && version.olderThan(out) }
        }

        override suspend fun install(update: AppUpdate, progress: (Float) -> Unit, installing: () -> Unit): Boolean {
            installed++
            return true
        }

        override fun restart() {}
    }

    private suspend fun TestScope.until(home: Home, done: () -> Boolean) {
        repeat(400) {
            advanceUntilIdle()
            if (done()) return
            withContext(Dispatchers.Default) { delay(5) }
        }
        val login = home.cloudflare.login
        error(
            "never got there: login $login ${(login as? Login.Confirm)?.let {
                "${it.plan} decided=${it.decided} problem=${it.problem}"
            }}, " +
                "app ${home.appUpdates.state}, status ${home.serverStatus}, due ${home.updating.due}, asked ${home.updating.asked}, message ${home.message}",
        )
    }

    @Test
    fun anUpdateShowsTheReleaseTheServerRunsOnceItRunsIt() = runTest {
        val prefs = MapPrefs().apply { saveOwn(Connection.ByCloudflare("https://realm.jo.workers.dev", login.copy(account = "a1"))) }
        val cloud = Cloud(this).apply {
            worker = true
            build = "v1.1.0"
        }
        val home = home(prefs, cloud)
        advanceUntilIdle()
        assertEquals(ServerStatus.Behind(Semver(1, 2, 0)), home.serverStatus)

        home.cloudflare.startUpdate()
        advanceUntilIdle()
        home.cloudflare.deploy(Choices())
        advanceUntilIdle()
        assertEquals("v1.2.0", cloud.build)
        assertEquals("realm v1.2.0", home.serverSettings.facts?.state?.value?.info?.running)
        assertEquals(ServerStatus.Current, home.serverStatus)
        assertEquals("Your server runs v1.2.0 now.", home.message)
    }

    @Test
    fun checkingShowsNoAnswerUntilTheAppHasLookedToo() = runTest {
        val prefs = MapPrefs().apply { saveOwn(Connection.ByCloudflare("https://realm.jo.workers.dev", login.copy(account = "a1"))) }
        val cloud = Cloud(this).apply {
            worker = true
            build = "v1.2.0"
        }
        val updater = Installs("1.2.0", Semver(1, 3, 0), released = false)
        val home = Home(TestPlatform(prefs, updater = updater), this, cloud.engine)
        advanceUntilIdle()
        assertEquals(UpdatesView.Current, home.updating.view)

        cloud.newApp = "app/v1.3.0"
        updater.released = true
        val shown = mutableListOf<UpdatesView>()
        updater.looking = { shown += home.updating.view }
        home.updating.check()
        advanceUntilIdle()
        assertEquals(listOf<UpdatesView>(UpdatesView.Checking), shown.distinct())
        assertIs<UpdatesView.Out>(home.updating.view)
    }

    @Test
    fun gitHubLimitingThisNetworkSaysSoThenAsksAgainByItself() = runTest {
        val prefs = MapPrefs().apply { saveOwn(Connection.ByCloudflare("https://realm.jo.workers.dev", login.copy(account = "a1"))) }
        val cloud = Cloud(this).apply {
            worker = true
            build = "v1.1.0"
            limitedUntil = Cloudflare.now() - 1
        }
        val home = home(prefs, cloud)
        runCurrent()
        assertIs<UpdatesView.Limited>(home.updating.view)

        cloud.limitedUntil = null
        advanceUntilIdle()
        assertNull(home.releases.limited)
        assertEquals(Due(null, Semver(1, 2, 0), null), home.updating.due)
    }

    @Test
    fun anUpdateAskedForWithATokenLogsInThenUpdatesTheServerWithoutAskingAgain() = runTest {
        val prefs = MapPrefs().apply { saveOwn(Connection.ByToken("https://realm.jo.workers.dev", Secret("token"))) }
        val cloud = Cloud(this).apply {
            worker = true
            build = "v1.1.0"
        }
        val home = home(prefs, cloud)
        advanceUntilIdle()
        assertEquals(Due(null, Semver(1, 2, 0), null), home.updating.due)

        home.updating.start()
        until(home) { cloud.build == "v1.2.0" && home.cloudflare.login == null }
        assertIs<Connection.ByCloudflare>(prefs.own())
        assertEquals(ServerStatus.Current, home.serverStatus)
        assertFalse(home.updating.asked)
    }

    @Test
    fun anUpdateAskedForUpdatesTheAppThenTheServerOnceTheAppRestarts() = runTest {
        val prefs = MapPrefs().apply { saveOwn(Connection.ByCloudflare("https://realm.jo.workers.dev", login.copy(account = "a1"))) }
        val cloud = Cloud(this).apply {
            worker = true
            build = "v1.1.0"
        }
        val before = Installs("1.2.0", Semver(1, 3, 0))
        val home = Home(TestPlatform(prefs, updater = before), this, cloud.engine)
        advanceUntilIdle()
        assertEquals(Due(Semver(1, 3, 0), Semver(1, 2, 0), null), home.updating.due)

        home.updating.start()
        until(home) { home.appUpdates.state is SelfUpdate.Ready }
        assertEquals(1, before.installed)
        assertEquals("v1.1.0", cloud.build)

        val after = Installs("1.3.0", Semver(1, 3, 0))
        val restarted = Home(TestPlatform(prefs, updater = after), this, cloud.engine)
        until(restarted) { cloud.build == "v1.2.0" && restarted.cloudflare.login == null }
        assertEquals(0, after.installed)
        assertFalse(restarted.updating.asked)
    }

    @Test
    fun anAppUpdateThatDidNotTakeIsNotTriedAgainOnTheNextStart() = runTest {
        val prefs = MapPrefs().apply { saveOwn(Connection.ByCloudflare("https://realm.jo.workers.dev", login.copy(account = "a1"))) }
        val cloud = Cloud(this).apply {
            worker = true
            build = "v1.1.0"
        }
        val home = Home(TestPlatform(prefs, updater = Installs("1.2.0", Semver(1, 3, 0))), this, cloud.engine)
        advanceUntilIdle()
        home.updating.start()
        until(home) { home.appUpdates.state is SelfUpdate.Ready }

        val declined = Installs("1.2.0", Semver(1, 3, 0))
        val restarted = Home(TestPlatform(prefs, updater = declined), this, cloud.engine)
        until(restarted) { !restarted.updating.asked }
        assertEquals(0, declined.installed)
        assertEquals("v1.1.0", cloud.build)
    }
}
