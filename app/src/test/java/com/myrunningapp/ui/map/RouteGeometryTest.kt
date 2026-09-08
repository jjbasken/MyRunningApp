package com.myrunningapp.ui.map

import com.myrunningapp.domain.Units
import com.myrunningapp.domain.tracking.Geo
import org.junit.Assert.*
import org.junit.Test

class RouteGeometryTest {
    private fun point(meters: Double, segment: Int = 0) =
        RouteCoordinate(Math.toDegrees(meters / 6_371_008.8), 0.0, segment)

    @Test fun `mile pins interpolate crossings and exclude partial final mile`() {
        val markers = mileMarkers(listOf(point(0.0), point(1000.0), point(3500.0)))
        assertEquals(listOf(1, 2), markers.map { it.number })
        markers.forEach {
            assertEquals(it.number * Units.METERS_PER_MILE,
                Geo.distanceMeters(0.0, 0.0, it.position.latitude, it.position.longitude), .001)
        }
    }

    @Test fun `pause gap adds no miles and crossing continues in resumed segment`() {
        val markers = mileMarkers(listOf(point(0.0), point(1000.0), point(10000.0, 1), point(11000.0, 1)))
        assertEquals(1, markers.size)
        assertEquals(1, markers.single().position.segmentIndex)
        assertEquals(point(10000.0 + Units.METERS_PER_MILE - 1000.0, 1).latitude,
            markers.single().position.latitude, .000001)
    }

    @Test fun `empty single and repeated points produce no mile pins`() {
        assertTrue(mileMarkers(emptyList()).isEmpty())
        assertTrue(mileMarkers(listOf(point(0.0))).isEmpty())
        assertTrue(mileMarkers(List(3) { point(0.0) }).isEmpty())
    }

    @Test fun `date line interpolation stays near the route`() {
        val markers = mileMarkers(listOf(RouteCoordinate(0.0, 179.99, 0), RouteCoordinate(0.0, -179.99, 0)))
        assertEquals(1, markers.size)
        assertTrue(kotlin.math.abs(markers.single().position.longitude) > 179.99)
    }
}
