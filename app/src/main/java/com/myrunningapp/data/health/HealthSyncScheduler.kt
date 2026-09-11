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
 * The work is unique-named with `APPEND_OR_REPLACE`, so finishing a run,
 * correcting three activity types and deleting a fourth run collapse into one
 * drain queued behind whatever is already running — rather than `REPLACE`,
 * which would cancel an in-flight drain outright. With `HealthSyncEngine.sync`
 * now paging through a whole backfill in one call, that in-flight drain can run
 * long, and `REPLACE` would let a run finishing mid-backfill repeatedly cut it
 * short. There are no constraints: Health Connect is local, so there is nothing
 * to wait for a network or a charger for.
 */
@Singleton
class HealthSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun requestSync() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            HealthSyncWorker.WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<HealthSyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }
}
