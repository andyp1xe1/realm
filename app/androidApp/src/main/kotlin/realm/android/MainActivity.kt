package realm.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import realm.ui.App

class MainActivity : ComponentActivity() {
    private lateinit var platform: AndroidPlatform

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        platform = AndroidPlatform(this)
        lifecycleScope.launch(Dispatchers.IO) { platform.sweepTempFiles() }
        val fromRecents = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromRecents) platform.handleShare(intent)
        setContent { App(platform) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        platform.handleShare(intent)
    }
}
