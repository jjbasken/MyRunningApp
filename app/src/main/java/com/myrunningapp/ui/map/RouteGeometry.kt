package com.myrunningapp.ui.map

import com.myrunningapp.domain.Units
import com.myrunningapp.domain.tracking.Geo

data class RouteCoordinate(val latitude: Double, val longitude: Double, val segmentIndex: Int)
data class RouteMileMarker(val number: Int, val position: RouteCoordinate)

/** Uses the same accepted points and distance math as tracking; pause gaps add no distance. */
fun mileMarkers(points: List<RouteCoordinate>): List<RouteMileMarker> = buildList {
    var distance = 0.0
    var nextMile = 1
    points.zipWithNext().forEach { (a, b) ->
        if (a.segmentIndex != b.segmentIndex) return@forEach
        val length = Geo.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        while (length > 0 && nextMile * Units.METERS_PER_MILE <= distance + length) {
            val fraction = (nextMile * Units.METERS_PER_MILE - distance) / length
            // Interpolate across the short side when crossing the date line.
            val longitudeDelta = ((b.longitude - a.longitude + 540) % 360) - 180
            val longitude = ((a.longitude + longitudeDelta * fraction + 540) % 360) - 180
            add(RouteMileMarker(nextMile++, RouteCoordinate(
                a.latitude + (b.latitude - a.latitude) * fraction, longitude, a.segmentIndex,
            )))
        }
        distance += length
    }
}
