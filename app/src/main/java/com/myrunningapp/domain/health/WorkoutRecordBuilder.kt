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

        // A split whose start and end offsets both clamp to the same instant
        // (e.g. a zero-duration split, or one that starts and ends on the same
        // segment boundary) would produce a zero-length ExerciseLap, which
        // Health Connect rejects; drop it rather than let it fail construction.
        var movingOffsetSec = 0L
        val laps = splits.sortedBy { it.splitNumber }.mapNotNull { split ->
            val startedAt = HealthTimeline.instantAt(segments, movingOffsetSec)
            movingOffsetSec += split.durationSec
            val endedAt = HealthTimeline.instantAt(segments, movingOffsetSec)
            if (!startedAt.isBefore(endedAt)) {
                null
            } else {
                HealthLap(startedAt = startedAt, endedAt = endedAt, distanceMeters = split.distanceMeters)
            }
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
                // Health Connect requires every route point's time to fall in
                // [startTime, endTime) — the end is strict. The last GPS fix
                // routinely lands exactly on endedAt, so that boundary point
                // must be excluded rather than kept.
                points
                    .filter { !it.timestamp.isBefore(run.startedAt) && it.timestamp.isBefore(run.endedAt) }
                    .sortedBy { it.timestamp }
                    .map { point ->
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
