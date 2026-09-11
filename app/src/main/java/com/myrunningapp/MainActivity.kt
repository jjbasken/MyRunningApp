package com.myrunningapp

import android.content.Intent
import android.net.Uri
import android.util.Log
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
        // Only on a genuinely new launch. getIntent() keeps returning the
        // rationale intent for the life of the activity, so an unguarded call
        // here reopens the privacy policy on every recreate — a rotation, a
        // theme or font-scale change, a process restart.
        if (savedInstanceState == null) handleHealthRationaleIntent(intent)
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
        setIntent(intent)
        handleHealthRationaleIntent(intent)
    }

    /**
     * Two manifest aliases — `HealthPermissionsRationaleActivity` for Android 13
     * and below, `ViewPermissionUsageActivity` for 14 and up — target this
     * activity with the rationale intent of their era. The spec requires that
     * rationale to show the privacy policy, so a
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
        // Spent once handled, so a later recreate reading the same sticky intent
        // falls through to the normal Track screen.
        intent.action = null
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
        }.onFailure { e -> Log.w(TAG, "Could not open the privacy policy", e) }
    }

    private companion object {
        const val TAG = "MainActivity"
        const val ACTION_SHOW_PERMISSIONS_RATIONALE = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
        const val ACTION_VIEW_PERMISSION_USAGE = "android.intent.action.VIEW_PERMISSION_USAGE"
        const val PRIVACY_POLICY_URL = "https://jjbasken.github.io/MyRunningApp/"
    }
}
