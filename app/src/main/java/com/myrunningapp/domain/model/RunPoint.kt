package com.myrunningapp.domain.model

import java.time.Instant

/**
 * One accepted GPS fix on a run's track (roughly 1 Hz).
 *
 * [segmentIndex] increases by one every time the run is paused and resumed, so
 * the map can draw a separate polyline per segment instead of a straight line
 * bridging the gap where the user stood still.
 */
data class RunPoint(
    val id: Long,
    val runId: Long,
    val timestamp: Instant,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val accuracyMeters: Float,
    val segmentIndex: Int,
)
