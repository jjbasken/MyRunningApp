package com.myrunningapp.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthSyncState
import com.myrunningapp.domain.model.Run
import java.time.Instant

@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Instant,
    val endedAt: Instant,
    val activityType: ActivityType,
    val distanceMeters: Double,
    val movingDurationSec: Long,
    val elapsedDurationSec: Long,
    val avgPaceSecPerMile: Double,
    val calories: Int,
    val weightKgAtRun: Double,
    @ColumnInfo(defaultValue = "0") val isInProgress: Boolean = false,
    @ColumnInfo(defaultValue = "0") val wasRecovered: Boolean = false,
    @ColumnInfo(defaultValue = "NOT_SYNCED")
    val healthSyncState: HealthSyncState = HealthSyncState.NOT_SYNCED,
    /**
     * Bumped every time the run is queued for Health Connect, and published as
     * the record's `clientRecordVersion`.
     *
     * It does two jobs. Health Connect keeps whichever copy of a
     * `clientRecordId` carries the higher version, so a rewrite that reused the
     * previous version could be discarded outright — an edit that never reached
     * the platform while the row claimed `SYNCED`. And because the engine
     * captures the version it wrote, it can refuse to mark a run `SYNCED` when
     * an edit re-queued it mid-write.
     */
    @ColumnInfo(defaultValue = "0") val healthSyncVersion: Long = 0,
) {
    fun toDomain(): Run = Run(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        activityType = activityType,
        distanceMeters = distanceMeters,
        movingDurationSec = movingDurationSec,
        elapsedDurationSec = elapsedDurationSec,
        avgPaceSecPerMile = avgPaceSecPerMile,
        calories = calories,
        weightKgAtRun = weightKgAtRun,
        wasRecovered = wasRecovered,
    )
}
