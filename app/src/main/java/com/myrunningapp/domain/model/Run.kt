package com.myrunningapp.domain.model

import java.time.Instant

/**
 * One completed activity. Summary fields are denormalised (also derivable from
 * the point/split rows) so the history list renders without touching the track.
 */
data class Run(
    val id: Long,
    val startedAt: Instant,
    val endedAt: Instant,
    val activityType: ActivityType,
    val distanceMeters: Double,
    /** Time spent actually moving — excludes paused stretches. */
    val movingDurationSec: Long,
    /** Wall-clock time from start to finish, including pauses. */
    val elapsedDurationSec: Long,
    val avgPaceSecPerMile: Double,
    val calories: Int,
    /** Weight used for this run's calorie estimate, frozen at finish time. */
    val weightKgAtRun: Double,
    val wasRecovered: Boolean = false,
)
