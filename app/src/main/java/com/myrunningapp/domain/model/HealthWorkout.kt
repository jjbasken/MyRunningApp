package com.myrunningapp.domain.model

import java.time.Instant

/**
 * One workout, described without reference to any platform type, ready to be
 * handed to Health Connect.
 *
 * Keeping this platform-free is what lets the mapping decisions
 * ([com.myrunningapp.domain.health.WorkoutRecordBuilder]) be unit-tested on the
 * JVM: only the gateway implementation ever mentions `androidx.health`.
 */
data class HealthWorkout(
    /** Stable per-run id. Health Connect upserts on it, so a rewrite is not a duplicate. */
    val clientRecordId: String,
    /**
     * Rises with every re-queue of the run. Health Connect keeps whichever copy
     * of a [clientRecordId] carries the higher version, so reusing one would let
     * it discard a rewrite and leave an edit unpublished.
     */
    val clientRecordVersion: Long,
    val activityType: ActivityType,
    val startedAt: Instant,
    val endedAt: Instant,
    val distanceMeters: Double,
    /** Active burn, not total: the MET estimate already excludes the resting baseline. */
    val activeCalories: Int,
    val title: String,
    /** The stretches the user was actually moving; the gaps between them are pauses. */
    val segments: List<HealthSegment>,
    val laps: List<HealthLap>,
    /** Empty when the route is not being shared. The rest of the workout is unaffected. */
    val route: List<HealthRoutePoint>,
)

data class HealthSegment(val startedAt: Instant, val endedAt: Instant)

data class HealthLap(
    val startedAt: Instant,
    val endedAt: Instant,
    val distanceMeters: Double,
)

data class HealthRoutePoint(
    val time: Instant,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val horizontalAccuracyMeters: Float,
)

/** The one place a run's Health Connect id is derived. */
fun healthClientRecordId(runId: Long): String = "run-$runId"
