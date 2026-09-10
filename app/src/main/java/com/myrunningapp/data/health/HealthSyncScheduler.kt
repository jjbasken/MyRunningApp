package com.myrunningapp.data.health

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks for a drain.
 *
 * The work is unique and replaces itself, so finishing a run, correcting three
 * activity types and deleting a fourth run collapse into one drain rather than
 * five. There are no constraints: Health Connect is local, so there is nothing
 * to wait for a network or a charger for.
 */
@Singleton
class HealthSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun requestSync() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            HealthSyncWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HealthSyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }
}
