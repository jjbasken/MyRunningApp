package com.myrunningapp.domain

import com.myrunningapp.domain.model.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {

    @Test
    fun `one mile is 1609 metres`() {
        assertEquals(1609.344, Units.milesToMeters(1.0), 0.001)
        assertEquals(1.0, Units.metersToMiles(1609.344), 1e-9)
    }

    @Test
    fun `pounds and kilograms round trip`() {
        val kg = Units.lbToKg(154.0)
        assertEquals(69.85, kg, 0.01)
        assertEquals(154.0, Units.kgToLb(kg), 1e-6)
    }

    @Test
    fun `inches and centimetres round trip`() {
        val cm = Units.inchesToCm(70.0)
        assertEquals(177.8, cm, 0.01)
        assertEquals(70.0, Units.cmToInches(cm), 1e-6)
    }

    @Test
    fun `formatDuration shows minutes and seconds below an hour`() {
        assertEquals("3:05", Units.formatDuration(185))
        assertEquals("0:09", Units.formatDuration(9))
    }

    @Test
    fun `formatDuration shows hours above an hour`() {
        assertEquals("1:02:05", Units.formatDuration(3725))
    }

    @Test
    fun `formatPace formats seconds per mile as minutes and seconds`() {
        assertEquals("9:05 /mi", Units.formatPace(545.0))
        assertEquals("10:00 /mi", Units.formatPace(600.0))
    }

    @Test
    fun `formatPace is graceful for zero or non-finite input`() {
        assertEquals("--:-- /mi", Units.formatPace(0.0))
        assertEquals("--:-- /mi", Units.formatPace(Double.NaN))
        assertEquals("--:-- /mi", Units.formatPace(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `paceSecPerMile divides duration by distance in miles`() {
        // 2 miles in 18:10 -> 9:05 per mile
        val pace = Units.paceSecPerMile(distanceMeters = 2 * Units.METERS_PER_MILE, durationSec = 1090)
        assertEquals(545.0, pace, 1e-6)
    }

    @Test
    fun `paceSecPerMile is NaN for zero distance`() {
        assertEquals(true, Units.paceSecPerMile(0.0, 100).isNaN())
    }

    @Test
    fun `formatSpeedMph turns seconds per mile into miles per hour`() {
        // 4 minutes per mile is 15 mph.
        assertEquals("15.0 mph", Units.formatSpeedMph(240.0))
        // 9:05 per mile.
        assertEquals("6.6 mph", Units.formatSpeedMph(545.0))
    }

    @Test
    fun `formatSpeedMph has no answer for a zero or non-finite pace`() {
        assertEquals("-- mph", Units.formatSpeedMph(0.0))
        assertEquals("-- mph", Units.formatSpeedMph(-1.0))
        assertEquals("-- mph", Units.formatSpeedMph(Double.NaN))
        assertEquals("-- mph", Units.formatSpeedMph(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `formatPaceOrSpeed reads a ride as speed and everything else as pace`() {
        assertEquals("15.0 mph", Units.formatPaceOrSpeed(240.0, ActivityType.BIKE))
        assertEquals("4:00 /mi", Units.formatPaceOrSpeed(240.0, ActivityType.RUN))
        assertEquals("4:00 /mi", Units.formatPaceOrSpeed(240.0, ActivityType.WALK))
    }
}
