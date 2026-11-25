package com.myrunningapp.data.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.myrunningapp.MainActivity
import com.myrunningapp.R
import com.myrunningapp.data.prefs.PreferencesRepository
import com.myrunningapp.domain.update.ReleaseInfo
import com.myrunningapp.domain.update.UpdateCheckResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The daily background check. It only ever notifies — installing stays a tap
 * in the app, because an install kills the process and must never land in the
 * middle of a run.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val updateManager: UpdateManager,
    private val preferences: PreferencesRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = preferences.preferences.first()
        if (!prefs.updateChecksEnabled) return Result.success()
        val result = updateManager.check()
        if (result is UpdateCheckResult.Available && result.release.tag != prefs.lastNotifiedUpdate) {
            if (notify(result.release)) preferences.setLastNotifiedUpdate(result.release.tag)
        }
        // A failed check just waits for tomorrow's; retrying offers nothing a day won't.
        return Result.success()
    }

    /** True if the notification was posted. Without notification permission there is nothing to do. */
    @SuppressLint("MissingPermission") // Checked at the top, for the API levels that have it.
    private fun notify(release: ReleaseInfo): Boolean {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.update_channel_description) },
        )
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_UPDATES, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_notification_title, release.version.toString()))
            .setContentText(context.getString(R.string.update_notification_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return runCatching { manager.notify(NOTIFICATION_ID, notification) }.isSuccess
    }

    companion object {
        const val WORK_NAME = "update-check"
        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFICATION_ID = 2001
    }
}

/** Turns the daily check on and off with the setting. WorkManager keeps it across reboots and updates. */
@Singleton
class UpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun apply(enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled || !UpdateManager.SUPPORTED) {
            workManager.cancelUniqueWork(UpdateCheckWorker.WORK_NAME)
            return
        }
        workManager.enqueueUniquePeriodicWork(
            UpdateCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build(),
        )
    }
}
