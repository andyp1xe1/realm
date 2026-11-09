package realm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppUpdatesTest {
    private val v120 = Semver(1, 2, 0)
    private val update = AppUpdate(v120, "https://github.com/andyp1xe1/realm/releases/download/v1.2.0/realm-v1.2.0.apk", "ab")
    private val prefs = MapPrefs()
    private var now = 1_000_000L
    private var looks = 0
    private var newest: AppUpdate? = update
    private var failing: Exception? = null

    private inner class FakeUpdater(installed: String = "1.1.0") : Updater {
        override val version = AppVersion.parse(installed)!!
        val downloaded = CompletableDeferred<Unit>()
        var answer = Installed.Ready
        var restarted = 0
        override suspend fun newer(): AppUpdate? {
            looks++
            failing?.let { throw it }
            return newest
        }

        override suspend fun download(update: AppUpdate, progress: (Float) -> Unit): Staged {
            progress(0.004f)
            progress(0.5f)
            downloaded.await()
            failing?.let { throw it }
            return Staged { answer }
        }

        override fun restart() {
            restarted++
        }
    }

    private fun TestScope.updates(updater: Updater? = FakeUpdater()) = AppUpdates(prefs, updater, backgroundScope) { now }

    @Test
    fun looksQuietlyAtMostOnceADay() = runTest {
        val u = updates()
        u.refresh()
        runCurrent()
        assertEquals(SelfUpdate.Available(v120), u.state)
        now += 24 * 60 * 60 - 1
        u.refresh()
        runCurrent()
        assertEquals(1, looks)
        now += 1
        u.refresh()
        runCurrent()
        assertEquals(2, looks)
    }

    @Test
    fun aQuietLookThatFailsSaysNothingAndCountsForNothing() = runTest {
        val u = updates()
        failing = ApiException("Can't reach GitHub")
        u.refresh()
        runCurrent()
        assertEquals(SelfUpdate.NotYet, u.state)
        failing = null
        u.refresh()
        runCurrent()
        assertEquals(SelfUpdate.Available(v120), u.state)
    }

    @Test
    fun theNextRunShowsTheNewerReleaseAtOnceUntilItIsInstalled() = runTest {
        updates().run {
            refresh()
            runCurrent()
        }
        assertTrue(updates().waiting)
        assertEquals(1, looks)
        assertEquals(SelfUpdate.NotYet, updates(FakeUpdater(installed = "1.2.0")).state)
    }

    @Test
    fun checkingSaysWhenItIsUpToDate() = runTest {
        newest = null
        val u = updates()
        u.check()
        assertEquals(SelfUpdate.Checking, u.state)
        runCurrent()
        assertEquals(SelfUpdate.UpToDate, u.state)
    }

    @Test
    fun installsWithProgressThenWaitsToRestart() = runTest {
        val updater = FakeUpdater()
        val u = updates(updater)
        u.install()
        runCurrent()
        assertEquals(SelfUpdate.Downloading(v120, 0.5f), u.state)
        updater.downloaded.complete(Unit)
        runCurrent()
        assertEquals(SelfUpdate.Ready(v120), u.state)
        u.check()
        now += 2 * 24 * 60 * 60
        u.refresh()
        runCurrent()
        assertEquals(SelfUpdate.Ready(v120), u.state)
        u.restart()
        assertEquals(1, updater.restarted)
    }

    @Test
    fun sayingNoLeavesTheUpdateWaiting() = runTest {
        val updater = FakeUpdater().apply {
            answer = Installed.Declined
            downloaded.complete(Unit)
        }
        val u = updates(updater)
        u.install()
        runCurrent()
        assertEquals(SelfUpdate.Available(v120), u.state)
        assertEquals(0, updater.restarted)
    }

    @Test
    fun aFailedInstallSaysWhyAndTriesTheInstallAgain() = runTest {
        val updater = FakeUpdater()
        val u = updates(updater)
        u.install()
        runCurrent()
        failing = DamagedDownload(v120)
        updater.downloaded.complete(Unit)
        runCurrent()
        val failed = assertIs<SelfUpdate.Failed>(u.state)
        assertTrue(failed.retriesInstall)
        assertEquals(DamagedDownload(v120).message, failed.message)
    }

    @Test
    fun aCopyThatCannotUpdateItselfNeverLooks() = runTest {
        val u = updates(updater = null)
        u.refresh()
        u.check()
        u.install()
        runCurrent()
        assertEquals(0, looks)
        assertEquals(SelfUpdate.NotYet, u.state)
        assertFalse(u.waiting)
    }
}
