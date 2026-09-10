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

    /** Oldest first, so a backfill publishes history in the order it happened. */
    @Query(
        """
        SELECT * FROM runs
        WHERE healthSyncState = 'PENDING' AND isInProgress = 0
        ORDER BY startedAt ASC
        LIMIT :limit
        """,
    )
    suspend fun pendingRuns(limit: Int = 50): List<RunEntity>

    @Query("UPDATE runs SET healthSyncState = :state WHERE id = :runId")
    suspend fun markState(runId: Long, state: HealthSyncState)

    /** Returns how many rows the backfill queued. */
    @Query(
        """
        UPDATE runs SET healthSyncState = 'PENDING'
        WHERE isInProgress = 0 AND healthSyncState = 'NOT_SYNCED'
        """,
    )
    suspend fun markAllPending(): Int

    @Query("UPDATE runs SET healthSyncState = 'PENDING' WHERE healthSyncState = 'FAILED'")
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
