package com.myrunningapp.domain.tracking

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle distance on a spherical Earth (haversine).
 *
 * Pure Kotlin on purpose: the whole tracking pipeline stays unit-testable off
 * device rather than leaning on `android.location.Location.distanceTo`. Over the
 * tens of metres between consecutive GPS fixes the difference from the ellipsoidal
 * answer is far below GPS noise.
 */
object Geo {

    /** Mean Earth radius (IUGG), metres. */
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun distanceMeters(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double,
    ): Double {
        val lat1 = Math.toRadians(latitude1)
        val lat2 = Math.toRadians(latitude2)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(longitude2 - longitude1)

        val h = sin(deltaLat / 2).pow(2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h).coerceAtMost(1.0))
    }
}
