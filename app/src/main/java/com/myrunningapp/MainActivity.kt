package com.myrunningapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.myrunningapp.ui.MainScreen
import com.myrunningapp.ui.theme.MyRunningTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleHealthRationaleIntent(intent)
        setContent {
            MyRunningTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // MainActivity's launch mode is the default "standard", so a fresh
        // rationale intent normally arrives via onCreate, not here — but this is
        // cheap insurance in case that ever changes (e.g. singleTop).
        handleHealthRationaleIntent(intent)
    }

    /**
     * The `HealthPermissionsRationaleActivity` alias (see the manifest) targets
     * this activity for both the pre-Android-14 and Android-14+ rationale
     * intents. The spec requires that rationale to show the privacy policy, so a
     * matching intent opens the published policy page instead of falling
     * through to the normal Track screen. `runCatching`: a device with no
     * browser must not crash the app.
     */
    private fun handleHealthRationaleIntent(intent: Intent) {
        if (intent.action != ACTION_SHOW_PERMISSIONS_RATIONALE &&
            intent.action != ACTION_VIEW_PERMISSION_USAGE
        ) {
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
        }
    }

    private companion object {
        const val ACTION_SHOW_PERMISSIONS_RATIONALE = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
        const val ACTION_VIEW_PERMISSION_USAGE = "android.intent.action.VIEW_PERMISSION_USAGE"
        const val PRIVACY_POLICY_URL = "https://jjbasken.github.io/MyRunningApp/"
    }
}
