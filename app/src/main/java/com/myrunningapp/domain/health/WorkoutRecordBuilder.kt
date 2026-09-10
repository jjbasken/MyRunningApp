package com.myrunningapp.domain.health

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthLap
import com.myrunningapp.domain.model.HealthRoutePoint
import com.myrunningapp.domain.model.HealthWorkout
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.RunPoint
import com.myrunningapp.domain.model.Split
import com.myrunningapp.domain.model.healthClientRecordId

/**
 * Turns a finished run into the workout that gets published.
 *
 * Every mapping decision lives here rather than in the gateway, so all of them
 * are unit-tested without a device or an installed Health Connect.
 */
object WorkoutRecordBuilder {

    /**
     * Returns null for a run with nothing in it. A zero-length session is
     * rejected by Health Connect anyway, and a run that never moved is not a
     * workout worth publishing.
     */
    fun build(
        run: Run,
        splits: List<Split>,
        points: List<RunPoint>,
        includeRoute: Boolean,
    ): HealthWorkout? {
        if (!run.endedAt.isAfter(run.startedAt)) return null
        if (run.distanceMeters <= 0.0) return null

        val segments = HealthTimeline.segments(run, points)

        var movingOffsetSec = 0L
        val laps = splits.sortedBy { it.splitNumber }.map { split ->
            val startedAt = HealthTimeline.instantAt(segments, movingOffsetSec)
            movingOffsetSec += split.durationSec
            HealthLap(
                startedAt = startedAt,
                endedAt = HealthTimeline.instantAt(segments, movingOffsetSec),
                distanceMeters = split.distanceMeters,
            )
        }

        return HealthWorkout(
            clientRecordId = healthClientRecordId(run.id),
            activityType = run.activityType,
            startedAt = run.startedAt,
            endedAt = run.endedAt,
            distanceMeters = run.distanceMeters,
            activeCalories = run.calories,
            title = when (run.activityType) {
                ActivityType.RUN -> "Run"
                ActivityType.WALK -> "Walk"
            },
            segments = segments,
            laps = laps,
            route = if (includeRoute) {
                points.sortedBy { it.timestamp }.map { point ->
                    HealthRoutePoint(
                        time = point.timestamp,
                        latitude = point.latitude,
                        longitude = point.longitude,
                        altitudeMeters = point.altitudeMeters,
                        horizontalAccuracyMeters = point.accuracyMeters,
                    )
                }
            } else {
                emptyList()
            },
        )
    }
}
