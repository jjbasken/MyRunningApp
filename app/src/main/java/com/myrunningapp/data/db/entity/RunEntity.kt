package com.myrunningapp.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.myrunningapp.domain.model.ActivityType
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
    )

    companion object {
        fun fromDomain(run: Run): RunEntity = RunEntity(
            id = run.id,
            startedAt = run.startedAt,
            endedAt = run.endedAt,
            activityType = run.activityType,
            distanceMeters = run.distanceMeters,
            movingDurationSec = run.movingDurationSec,
            elapsedDurationSec = run.elapsedDurationSec,
            avgPaceSecPerMile = run.avgPaceSecPerMile,
            calories = run.calories,
            weightKgAtRun = run.weightKgAtRun,
        )
    }
}
