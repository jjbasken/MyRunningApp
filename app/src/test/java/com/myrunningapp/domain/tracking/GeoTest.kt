package com.myrunningapp.domain.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {

    @Test
    fun `distance between a point and itself is zero`() {
        assertEquals(0.0, Geo.distanceMeters(51.5007, -0.1246, 51.5007, -0.1246), 1e-9)
    }

    @Test
    fun `one degree of latitude is about 111 kilometres`() {
        val meters = Geo.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, meters, 200.0)
    }

    @Test
    fun `distance matches a known city pair`() {
        // Big Ben to the Eiffel Tower: ~340.5 km great-circle.
        val meters = Geo.distanceMeters(51.5007, -0.1246, 48.8584, 2.2945)
        assertEquals(340_540.0, meters, 1_000.0)
    }

    @Test
    fun `a short east-west step shrinks with latitude`() {
        val atEquator = Geo.distanceMeters(0.0, 0.0, 0.0, 0.001)
        val atSixty = Geo.distanceMeters(60.0, 0.0, 60.0, 0.001)
        // cos(60 degrees) = 0.5, so the same longitude step is half as long.
        assertEquals(atEquator / 2.0, atSixty, 0.1)
    }
}
