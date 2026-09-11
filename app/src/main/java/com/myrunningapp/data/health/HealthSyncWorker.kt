package com.myrunningapp.data.health

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Drains the outbox in the background. The decision is [HealthSyncResults]'. */
@HiltWorker
class HealthSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: HealthSyncEngine,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = when (HealthSyncResults.forOutcome(engine.sync())) {
        HealthSyncWorkerResult.SUCCESS -> Result.success()
        HealthSyncWorkerResult.RETRY -> Result.retry()
    }

    companion object {
        const val WORK_NAME = "health-sync"
    }
}
