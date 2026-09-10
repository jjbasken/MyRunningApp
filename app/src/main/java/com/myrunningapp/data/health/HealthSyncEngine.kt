package com.myrunningapp.data.health

import com.myrunningapp.data.db.dao.HealthSyncDao
import com.myrunningapp.data.db.dao.RunPointDao
import com.myrunningapp.data.db.dao.SplitDao
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.domain.health.HealthAvailability
import com.myrunningapp.domain.health.WorkoutRecordBuilder
import com.myrunningapp.domain.model.HealthSyncState
import com.myrunningapp.domain.model.healthClientRecordId
import javax.inject.Inject
import javax.inject.Singleton

/** How a drain ended. The worker turns this into success, retry or failure. */
enum class HealthSyncOutcome {
    /** The toggle is off. Nothing was touched. */
    DISABLED,

    /** No usable Health Connect on this phone. */
    UNAVAILABLE,

    /** Not granted, or revoked partway. The queue is intact and nothing failed. */
    PERMISSION_MISSING,

    /** The queue drained. Individual runs may have been marked FAILED. */
    COMPLETED,

    /** Something was temporarily unable; run again later. */
    RETRY_LATER,
}

/**
 * Drains the outbox into Health Connect.
 *
 * Every step is idempotent, because every record carries a stable
 * `clientRecordId`: writing twice updates rather than duplicates, and deleting
 * something already gone is not an error. That is what makes retrying safe.
 *
 * Missing permission is deliberately *not* a write failure. The user can revoke
 * at any moment, including mid-backfill, and treating that as failure would burn
 * the whole queue for something they may re-grant a minute later.
 */
@Singleton
class HealthSyncEngine @Inject constructor(
    private val healthSyncDao: HealthSyncDao,
    private val runPointDao: RunPointDao,
    private val splitDao: SplitDao,
    private val gateway: HealthConnectGateway,
    private val syncEnabled: suspend () -> Boolean,
) {

    suspend fun sync(): HealthSyncOutcome {
        if (!syncEnabled()) return HealthSyncOutcome.DISABLED
        if (gateway.availability() != HealthAvailability.AVAILABLE) {
            return HealthSyncOutcome.UNAVAILABLE
        }
        if (!gateway.hasWritePermissions()) return HealthSyncOutcome.PERMISSION_MISSING

        var retryLater = false

        // Deletions first: a delete that lost a race with a stale write would
        // otherwise resurrect a run the user got rid of.
        for (deletion in healthSyncDao.pendingDeletions()) {
            when (gateway.delete(healthClientRecordId(deletion.runId))) {
                is HealthWriteResult.Success -> healthSyncDao.clearDeletion(deletion.runId)
                is HealthWriteResult.PermissionMissing -> return HealthSyncOutcome.PERMISSION_MISSING
                is HealthWriteResult.Retryable -> retryLater = true
                // A record Health Connect will not delete is one we stop asking about.
                is HealthWriteResult.Rejected -> healthSyncDao.clearDeletion(deletion.runId)
            }
        }

        val includeRoute = gateway.hasRoutePermission()

        for (run in healthSyncDao.pendingRuns()) {
            val workout = buildWorkout(run, includeRoute)
            if (workout == null) {
                // Nothing to publish and nothing time will fix.
                healthSyncDao.markState(run.id, HealthSyncState.FAILED)
                continue
            }
            when (gateway.write(workout)) {
                is HealthWriteResult.Success ->
                    healthSyncDao.markState(run.id, HealthSyncState.SYNCED)
                is HealthWriteResult.PermissionMissing ->
                    return HealthSyncOutcome.PERMISSION_MISSING
                is HealthWriteResult.Retryable -> retryLater = true
                is HealthWriteResult.Rejected ->
                    healthSyncDao.markState(run.id, HealthSyncState.FAILED)
            }
        }

        return if (retryLater) HealthSyncOutcome.RETRY_LATER else HealthSyncOutcome.COMPLETED
    }

    private suspend fun buildWorkout(run: RunEntity, includeRoute: Boolean) =
        WorkoutRecordBuilder.build(
            run = run.toDomain(),
            splits = splitDao.getForRun(run.id).map { it.toDomain() },
            points = runPointDao.getForRun(run.id).map { it.toDomain() },
            includeRoute = includeRoute,
        )
}
