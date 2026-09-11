package com.myrunningapp.data.health

import android.util.Log
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
        // Deletions queued while sync is off are deliberately left sitting in
        // health_deletions until sync is re-enabled: "off leaves already-written
        // data alone" means Health Connect is not touched at all while off, not
        // just that writes are skipped. Not a bug — this is why deletions are
        // drained below rather than before this check.
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

        // The queue is drained one LIMIT-sized page at a time, oldest first, so a
        // backfill of hundreds of runs does not have to be held in memory at once.
        //
        // Paging is by `(startedAt, id)` cursor rather than by re-asking for the
        // oldest pending rows. A run whose write comes back Retryable stays
        // PENDING, so a re-asking loop would be handed the same rows again: it
        // would either spin, or — the reason this matters — stall for good once a
        // page's worth of them piled up at the head of the queue, and no run
        // recorded afterwards would ever be published. A cursor that only ever
        // moves forward visits every pending run exactly once and terminates on
        // the first short page, whatever each write returns.
        var afterStartedAt = Long.MIN_VALUE
        var afterId = Long.MIN_VALUE
        while (true) {
            val page = healthSyncDao.pendingRunsAfter(afterStartedAt, afterId, PAGE_SIZE)
            if (page.isEmpty()) break

            for (run in page) {
                // Captured before the write: `markOutcome` refuses to record a
                // verdict against a run an edit has since re-queued.
                val version = run.healthSyncVersion
                val workout = buildWorkout(run, includeRoute, version)
                if (workout == null) {
                    // Nothing to publish and nothing time will fix — but this is not
                    // the same as a rejection, so it must not read as "could not be
                    // written" and must not be retryable via Sync now.
                    healthSyncDao.markOutcome(run.id, HealthSyncState.NOT_APPLICABLE, version)
                    continue
                }
                when (val result = gateway.write(workout)) {
                    is HealthWriteResult.Success ->
                        healthSyncDao.markOutcome(run.id, HealthSyncState.SYNCED, version)
                    is HealthWriteResult.PermissionMissing ->
                        return HealthSyncOutcome.PERMISSION_MISSING
                    is HealthWriteResult.Retryable -> retryLater = true
                    is HealthWriteResult.Rejected -> {
                        Log.w(TAG, "Run ${run.id} rejected by Health Connect: ${result.reason}")
                        healthSyncDao.markOutcome(run.id, HealthSyncState.FAILED, version)
                    }
                }
            }

            if (page.size < PAGE_SIZE) break
            val last = page.last()
            afterStartedAt = last.startedAt.toEpochMilli()
            afterId = last.id
        }

        return if (retryLater) HealthSyncOutcome.RETRY_LATER else HealthSyncOutcome.COMPLETED
    }

    private suspend fun buildWorkout(run: RunEntity, includeRoute: Boolean, version: Long) =
        WorkoutRecordBuilder.build(
            run = run.toDomain(),
            splits = splitDao.getForRun(run.id).map { it.toDomain() },
            points = runPointDao.getForRun(run.id).map { it.toDomain() },
            includeRoute = includeRoute,
            clientRecordVersion = version,
        )

    private companion object {
        const val TAG = "HealthSyncEngine"

        /** How many runs one page of the drain holds. */
        const val PAGE_SIZE = 50
    }
}
