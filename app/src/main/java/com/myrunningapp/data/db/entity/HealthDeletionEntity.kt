package com.myrunningapp.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * A run that was deleted locally and still needs deleting from Health Connect.
 *
 * This is a table rather than a column because deleting a run destroys the row
 * that would have remembered the deletion. Deliberately no foreign key: the
 * whole point is that the run is gone.
 */
@Entity(tableName = "health_deletions")
data class HealthDeletionEntity(
    @PrimaryKey val runId: Long,
    val requestedAt: Instant,
)
