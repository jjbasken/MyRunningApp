package com.myrunningapp.domain.tracking

import java.time.Instant

/**
 * A single location reading, stripped of every Android type so the tracking
 * pipeline can be driven from tests and from the GPS replay harness.
 *
 * Created from `android.location.Location` at the service boundary; becomes a
 * [com.myrunningapp.domain.model.RunPoint] once the run accepts it.
 */
data class GpsFix(
    val timestamp: Instant,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val accuracyMeters: Float,
)
