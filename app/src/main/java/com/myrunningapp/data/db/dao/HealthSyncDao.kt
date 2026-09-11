package com.myrunningapp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.myrunningapp.data.db.entity.HealthDeletionEntity
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.domain.model.HealthSyncCounts
import com.myrunningapp.domain.model.HealthSyncState
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthSyncDao {

    /**
     * One page of pending runs strictly after the `(startedAt, id)` cursor,
     * oldest first, so a backfill publishes history in the order it happened.
     *
     * The cursor is what keeps a stuck run from starving every run behind it. A
     * run whose write comes back retryable stays `PENDING`, so an offset-free
     * "oldest pending" query would hand back the same rows on the next page and
     * never reach anything newer — once a page's worth of them accumulated, no
     * run recorded afterwards could ever be published. Paging past them instead
     * means each drain visits every pending run exactly once.
     */
    @Query(
        """
        SELECT * FROM runs
        WHERE healthSyncState = 'PENDING' AND isInProgress = 0
          AND (startedAt > :afterStartedAt
               OR (startedAt = :afterStartedAt AND id > :afterId))
        ORDER BY startedAt ASC, id ASC
        LIMIT :limit
        """,
    )
    suspend fun pendingRunsAfter(afterStartedAt: Long, afterId: Long, limit: Int): List<RunEntity>

    /** The first page: everything is after a cursor no stored run can sit at or before. */
    suspend fun pendingRuns(limit: Int = 50): List<RunEntity> =
        pendingRunsAfter(afterStartedAt = Long.MIN_VALUE, afterId = Long.MIN_VALUE, limit = limit)

    /**
     * Queues one run and bumps its version, so the rewrite outranks whatever
     * Health Connect already holds under the same client record id.
     */
    @Query(
        """
        UPDATE runs
        SET healthSyncState = 'PENDING', healthSyncVersion = healthSyncVersion + 1
        WHERE id = :runId
        """,
    )
    suspend fun markPending(runId: Long)

    /**
     * Records how a drain ended for one run — but only if that run is still the
     * one that was written.
     *
     * The version is the one captured before the write went out. An edit landing
     * while the write was in flight re-queues the run at a higher version, and
     * this update then matches nothing: the row stays `PENDING` and the edit
     * gets published, rather than being buried under a `SYNCED` (or `FAILED`)
     * describing the copy that is already stale.
     */
    @Query(
        """
        UPDATE runs SET healthSyncState = :state
        WHERE id = :runId AND healthSyncState = 'PENDING' AND healthSyncVersion = :version
        """,
    )
    suspend fun markOutcome(runId: Long, state: HealthSyncState, version: Long)

    /** Unconditional; for tests and for states that are not a drain's verdict. */
    @Query("UPDATE runs SET healthSyncState = :state WHERE id = :runId")
    suspend fun markState(runId: Long, state: HealthSyncState)

    /** Returns how many rows the backfill queued. */
    @Query(
        """
        UPDATE runs
        SET healthSyncState = 'PENDING', healthSyncVersion = healthSyncVersion + 1
        WHERE isInProgress = 0 AND healthSyncState = 'NOT_SYNCED'
        """,
    )
    suspend fun markAllPending(): Int

    @Query(
        """
        UPDATE runs
        SET healthSyncState = 'PENDING', healthSyncVersion = healthSyncVersion + 1
        WHERE healthSyncState = 'FAILED' AND isInProgress = 0
        """,
    )
    suspend fun retryFailed(): Int

    /** Replaces on conflict: a second delete request for the same run is the same request. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun queueDeletion(deletion: HealthDeletionEntity)

    @Query("SELECT * FROM health_deletions ORDER BY requestedAt ASC")
    suspend fun pendingDeletions(): List<HealthDeletionEntity>

    @Query("DELETE FROM health_deletions WHERE runId = :runId")
    suspend fun clearDeletion(runId: Long)

    @Query(
        """
        SELECT
            COALESCE(SUM(healthSyncState = 'PENDING'), 0) AS pending,
            COALESCE(SUM(healthSyncState = 'SYNCED'), 0) AS synced,
            COALESCE(SUM(healthSyncState = 'FAILED'), 0) AS failed
        FROM runs WHERE isInProgress = 0
        """,
    )
    fun observeCounts(): Flow<HealthSyncCounts>
}
