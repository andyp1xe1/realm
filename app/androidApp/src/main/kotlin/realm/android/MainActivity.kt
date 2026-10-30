package realm.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import realm.ui.App

class MainActivity : ComponentActivity() {
    private lateinit var platform: AndroidPlatform

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        platform = AndroidPlatform(this)
        if (savedInstanceState == null) platform.handleShare(intent)
        setContent { App(platform) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        platform.handleShare(intent)
    }
}
