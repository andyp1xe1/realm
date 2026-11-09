package realm.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import realm.ApiException
import realm.AppUpdate
import realm.AppVersion
import realm.Installed
import realm.Staged
import realm.Updater
import realm.download
import realm.newerApp
import java.io.File

/** Replaces the installed APK with a release's, through Android's package installer, which asks the user first. */
internal class AndroidUpdater(private val activity: ComponentActivity, override val version: AppVersion) : Updater {
    private val apk get() = File(File(activity.cacheDir, "update"), "realm.apk")

    override suspend fun newer() = newerApp(version, { tag -> "realm-$tag.apk" })

    override suspend fun download(update: AppUpdate, progress: (Float) -> Unit): Staged {
        update.download(apk, progress = progress)
        return Staged { install() }
    }

    private suspend fun install(): Installed {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(activity.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        val action = "${activity.packageName}.INSTALLED.$id"
        val results = Channel<Intent>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                results.trySend(intent)
            }
        }
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        var committed = false
        try {
            installer.openSession(id).use { session ->
                withContext(Dispatchers.IO) {
                    session.openWrite("realm.apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                }
                val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val done = PendingIntent.getBroadcast(activity, id, Intent(action).setPackage(activity.packageName), mutable)
                session.commit(done.intentSender)
                committed = true
            }
            while (true) {
                val result = results.receive()
                when (result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> result.confirmation()?.let(activity::startActivity)

                    PackageInstaller.STATUS_SUCCESS -> return Installed.Ready

                    PackageInstaller.STATUS_FAILURE_ABORTED -> return Installed.Declined

                    else -> throw ApiException(
                        result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android didn't install the update.",
                    )
                }
            }
        } finally {
            if (!committed) installer.abandonSession(id)
            activity.unregisterReceiver(receiver)
            apk.delete()
        }
    }

    override fun restart() {
        val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        activity.finish()
    }
}

@Suppress("DEPRECATION")
private fun Intent.confirmation(): Intent? = if (Build.VERSION.SDK_INT >=
    33
) {
    getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
} else {
    getParcelableExtra(Intent.EXTRA_INTENT)
}
