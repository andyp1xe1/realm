// Filmstrips of settings' app section: checking finds the app up to date, and a newer release that the daily
// look found is downloaded and put in place, waiting for a restart.
package realm.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import realm.AppUpdate
import realm.AppVersion
import realm.Home
import realm.Installed
import realm.SelfUpdate
import realm.Semver
import realm.Staged
import realm.Updater

/** A copy at 0.2.0 that can replace itself with [newest]; GitHub answers once for each of [answers], and a download goes as far as [steps] say. */
private class PreviewUpdater(private val newest: AppUpdate?) : Updater {
    override val version = AppVersion.parse("0.2.0")!!
    val answers = Channel<Unit>(Channel.UNLIMITED)
    val steps = Channel<Float>(Channel.UNLIMITED)

    override suspend fun newer(): AppUpdate? {
        answers.receive()
        return newest
    }

    override suspend fun download(update: AppUpdate, progress: (Float) -> Unit): Staged {
        for (p in steps) {
            progress(p)
            if (p >= 1f) break
        }
        return Staged { Installed.Ready }
    }

    override fun restart() {}
}

/**
 * app-uptodate-N: settings open, and checking finds the app up to date again, the answer coming while "Checking" is
 * still fading in. app-update-N: v0.3.0 is out, downloaded, and put in place.
 */
internal fun selfUpdating() {
    val upToDate = PreviewUpdater(newest = null)
    val current = Home(FakePlatform(updater = upToDate), CoroutineScope(Ui))
    Film(current, settings = true).apply {
        upToDate.answers.trySend(Unit)
        until { current.appUpdates.state == SelfUpdate.UpToDate }
        roll("app-uptodate", max = 24)
        current.appUpdates.check()
        roll("app-uptodate", every = 1, max = 10)
        upToDate.answers.trySend(Unit)
        roll("app-uptodate", every = 1, max = 30)
        roll("app-uptodate", max = 30)
        close()
    }

    val updater = PreviewUpdater(AppUpdate(Semver(0, 3, 0), "https://github.com/andyp1xe1/realm/releases/download/v0.3.0/realm.apk", ""))
    val older = Home(FakePlatform(updater = updater), CoroutineScope(Ui))
    Film(older, settings = true).apply {
        roll("app-update", max = 24)
        updater.answers.trySend(Unit)
        roll("app-update", max = 100) { older.appUpdates.state is SelfUpdate.Available }
        roll("app-update", max = 24)
        updater.answers.trySend(Unit)
        older.appUpdates.install()
        for (p in listOf(0.1f, 0.35f, 0.6f, 0.85f, 1f)) {
            updater.steps.trySend(p)
            roll("app-update", max = 12)
        }
        roll("app-update", max = 100) { older.appUpdates.state is SelfUpdate.Ready }
        roll("app-update", max = 30)
        check(older.appUpdates.waiting) { "app-update: no restart waiting" }
        close()
    }
}
