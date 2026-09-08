package com.myrunningapp.data.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.myrunningapp.MainActivity
import com.myrunningapp.R
import com.myrunningapp.domain.Units
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.RunSessionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps a run alive: a foreground service so Android leaves the process running
 * with the screen off, a wakelock so fixes keep arriving, and the GPS subscription
 * itself. The run's state lives in [RunTracker]; this class only feeds it and
 * mirrors it into the notification.
 *
 * Started by the track screen and stopped as soon as the run ends.
 */
@AndroidEntryPoint
class LocationTrackingService : LifecycleService() {

    @Inject lateinit var tracker: RunTracker
    @Inject lateinit var locationClient: LocationClient

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_PAUSE -> lifecycleScope.launch { tracker.pause() }
            ACTION_RESUME -> lifecycleScope.launch { tracker.resume() }
            ACTION_SKIP_COUNTDOWN -> lifecycleScope.launch { tracker.skipCountdown() }
            ACTION_CANCEL -> lifecycleScope.launch { tracker.cancel() }
            ACTION_FINISH -> lifecycleScope.launch { tracker.finish() }
        }
        // The run should outlive a low-memory kill of the UI, but restarting the
        // service with no intent could not resume a run that is already gone.
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (wakeLock != null) return // already running

        val activityType = ActivityType.valueOf(
            intent.getStringExtra(EXTRA_ACTIVITY_TYPE) ?: ActivityType.RUN.name,
        )
        val countdownSeconds = intent.getIntExtra(EXTRA_COUNTDOWN_SECONDS, 0)
        val weightKg = intent.getDoubleExtra(EXTRA_WEIGHT_KG, 0.0)

        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.track_notification_starting)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
        acquireWakeLock()

        lifecycleScope.launch {
            // Strictly after start: the tracker is still IDLE until it returns, and
            // the notification mirror stops the service the moment it sees IDLE.
            tracker.start(activityType, countdownSeconds, weightKg)
            collectFixes()
            keepTheClockMoving()
            mirrorIntoNotification()
        }
    }

    private fun collectFixes() = lifecycleScope.launch {
        locationClient.fixes().collect { fix -> tracker.onLocation(fix) }
    }

    /** The timer and the countdown have to advance even when no fix arrives. */
    private fun keepTheClockMoving() = lifecycleScope.launch {
        while (isActive) {
            delay(TICK_MILLIS)
            tracker.tick()
        }
    }

    private fun mirrorIntoNotification() = lifecycleScope.launch {
        tracker.snapshot.collectLatest { snapshot ->
            if (snapshot.state == RunSessionState.IDLE) {
                stopSelf()
                return@collectLatest
            }
            val text = when (snapshot.state) {
                RunSessionState.COUNTDOWN -> getString(
                    R.string.track_notification_countdown,
                    snapshot.countdownSecondsRemaining,
                )
                RunSessionState.PAUSED -> getString(R.string.track_notification_paused)
                else -> getString(
                    R.string.track_notification_running,
                    Units.formatMiles(snapshot.distanceMeters),
                    Units.formatDuration(snapshot.movingDurationSec),
                )
            }
            notificationManager().notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.track_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.track_notification_channel_description)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Without this the CPU sleeps with the screen and fixes stop arriving, which
     * is exactly the case the app exists for — a phone in a pocket.
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MyRunningApp:tracking",
        ).apply { acquire(MAX_RUN_MILLIS) }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.myrunningapp.action.START"
        const val ACTION_PAUSE = "com.myrunningapp.action.PAUSE"
        const val ACTION_RESUME = "com.myrunningapp.action.RESUME"
        const val ACTION_SKIP_COUNTDOWN = "com.myrunningapp.action.SKIP_COUNTDOWN"
        const val ACTION_CANCEL = "com.myrunningapp.action.CANCEL"
        const val ACTION_FINISH = "com.myrunningapp.action.FINISH"

        private const val EXTRA_ACTIVITY_TYPE = "activity_type"
        private const val EXTRA_COUNTDOWN_SECONDS = "countdown_seconds"
        private const val EXTRA_WEIGHT_KG = "weight_kg"

        private const val CHANNEL_ID = "run_tracking"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MILLIS = 1_000L
        /** A generous ceiling; the lock is released as soon as the run ends. */
        private const val MAX_RUN_MILLIS = 6 * 60 * 60 * 1000L

        fun startIntent(
            context: Context,
            activityType: ActivityType,
            countdownSeconds: Int,
            weightKg: Double,
        ): Intent = Intent(context, LocationTrackingService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_ACTIVITY_TYPE, activityType.name)
            putExtra(EXTRA_COUNTDOWN_SECONDS, countdownSeconds)
            putExtra(EXTRA_WEIGHT_KG, weightKg)
        }

        fun commandIntent(context: Context, action: String): Intent =
            Intent(context, LocationTrackingService::class.java).apply {
                this.action = action
            }
    }
}
