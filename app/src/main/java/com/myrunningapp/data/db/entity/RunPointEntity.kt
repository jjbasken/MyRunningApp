package com.myrunningapp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myrunningapp.domain.model.RunPoint
import java.time.Instant

@Entity(
    tableName = "run_points",
    foreignKeys = [
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("runId")],
)
data class RunPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val timestamp: Instant,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val accuracyMeters: Float,
    val segmentIndex: Int,
) {
    fun toDomain(): RunPoint = RunPoint(
        id = id,
        runId = runId,
        timestamp = timestamp,
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = altitudeMeters,
        accuracyMeters = accuracyMeters,
        segmentIndex = segmentIndex,
    )
}
