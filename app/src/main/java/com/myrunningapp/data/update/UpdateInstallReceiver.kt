package com.myrunningapp.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Receives the package installer's answer to [UpdateManager]'s session.
 *
 * `STATUS_PENDING_USER_ACTION` means Android wants the user to confirm, and
 * hands over the confirmation screen to start. On success nothing arrives here
 * in practice: replacing the app kills this process first.
 *
 * Reaches [UpdateManager] through an entry point rather than
 * `@AndroidEntryPoint`, which needs `super.onReceive` semantics an abstract
 * method doesn't have.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UpdateReceiverEntryPoint {
        fun updateManager(): UpdateManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        val updateManager = EntryPointAccessors
            .fromApplication(context.applicationContext, UpdateReceiverEntryPoint::class.java)
            .updateManager()
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm == null) {
                updateManager.onInstallResult(PackageInstaller.STATUS_FAILURE, "The installer gave no confirmation screen")
                return
            }
            // Recorded first, so the screen can offer the dialog itself if
            // Android blocks this start because the app is in the background.
            updateManager.onConfirmationNeeded(confirm)
            runCatching {
                context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { e -> Log.w(TAG, "Could not open the install confirmation", e) }
            return
        }
        updateManager.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    private companion object {
        const val TAG = "UpdateInstallReceiver"
    }
}
