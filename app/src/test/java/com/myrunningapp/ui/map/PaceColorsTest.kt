package com.myrunningapp.ui.map

import com.myrunningapp.domain.tracking.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceColorsTest {

    /**
     * Builds a straight north-bound track where each leg covers [metersPerStep]
     * in [secondsPerStep], so a stretch's pace is known exactly.
     */
    private fun track(
        legs: List<Pair<Double, Double>>,
        segmentIndex: Int = 0,
        startMillis: Long = 0L,
    ): List<RouteCoordinate> {
        var latitude = 47.62
        var millis = startMillis
        val points = mutableListOf(
            RouteCoordinate(latitude, -122.35, segmentIndex, millis),
        )
        legs.forEach { (metersPerStep, secondsPerStep) ->
            latitude += metersPerStep / 111_320.0 // metres per degree of latitude
            millis += (secondsPerStep * 1000).toLong()
            points.add(RouteCoordinate(latitude, -122.35, segmentIndex, millis))
        }
        return points
    }

    private fun steadyLegs(count: Int, meters: Double, seconds: Double) =
        List(count) { meters to seconds }

    @Test
    fun `a route too short to have a spread gets no bands`() {
        assertTrue(PaceColors.bands(track(steadyLegs(1, 20.0, 10.0))).isEmpty())
        assertTrue(PaceColors.bands(emptyList()).isEmpty())
    }

    @Test
    fun `points with no timestamps get no bands`() {
        val untimed = List(20) { RouteCoordinate(47.62 + it * 0.001, -122.35, 0) }
        assertTrue(PaceColors.bands(untimed).isEmpty())
    }

    @Test
    fun `chunks are roughly a hundred metres`() {
        val bands = PaceColors.bands(track(steadyLegs(40, 20.0, 10.0)))
        // Every band but the last runs until it passes 100 m, so it lands
        // between 100 m and 100 m plus one leg. The last one is the remainder.
        bands.dropLast(1).forEach { band ->
            val length = length(band)
            assertTrue("band was $length m", length >= 100.0 && length < 125.0)
        }
        assertTrue("the remainder should not exceed a full chunk", length(bands.last()) < 125.0)
    }

    private fun length(band: PaceBand): Double =
        band.points.zipWithNext().sumOf { (a, b) ->
            Geo.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        }

    @Test
    fun `bands join end to end so the drawn line has no gaps`() {
        val bands = PaceColors.bands(track(steadyLegs(40, 20.0, 10.0)))
        bands.zipWithNext().forEach { (first, second) ->
            assertEquals(first.points.last(), second.points.first())
        }
    }

    @Test
    fun `band pace matches the pace actually run`() {
        // 20 m every 10 s is 2 m/s; a mile is 1609.344 m, so 804.7 s per mile.
        val bands = PaceColors.bands(track(steadyLegs(40, 20.0, 10.0)))
        bands.forEach { assertEquals(804.7, it.paceSecPerMile, 5.0) }
    }

    @Test
    fun `a run held at one pace is drawn in one colour`() {
        val bands = PaceColors.bands(track(steadyLegs(40, 20.0, 10.0)))
        assertEquals(1, bands.map { it.rgb }.distinct().size)
    }

    @Test
    fun `the slow half is redder than the fast half`() {
        val fastThenSlow = steadyLegs(20, 25.0, 8.0) + steadyLegs(20, 10.0, 12.0)
        val bands = PaceColors.bands(track(fastThenSlow))
        val first = bands.first()
        val last = bands.last()
        assertTrue("expected the opening stretch to be the faster one",
            first.paceSecPerMile < last.paceSecPerMile)
        assertTrue("expected the slower stretch to carry more red",
            red(last.rgb) > red(first.rgb))
        assertTrue("expected the faster stretch to carry more green",
            green(first.rgb) > green(last.rgb))
    }

    @Test
    fun `a pause does not colour the gap as a crawl`() {
        // Two segments, ten minutes apart on the clock but adjacent in space:
        // the bridging leg must not become a band.
        val before = track(steadyLegs(20, 20.0, 10.0), segmentIndex = 0, startMillis = 0)
        val after = track(steadyLegs(20, 20.0, 10.0), segmentIndex = 1, startMillis = 600_000)
        val bands = PaceColors.bands(before + after)
        bands.forEach { band ->
            assertEquals(
                "a band must not straddle a pause",
                1,
                band.points.map { it.segmentIndex }.distinct().size,
            )
            assertTrue("pace should stay plausible across the pause", band.paceSecPerMile < 2000)
        }
    }

    @Test
    fun `colours clamp outside the ramp rather than running off it`() {
        val fastest = PaceColors.colorFor(300.0, fast = 480.0, slow = 720.0)
        val beyond = PaceColors.colorFor(60.0, fast = 480.0, slow = 720.0)
        assertEquals(fastest, beyond)

        val slowest = PaceColors.colorFor(900.0, fast = 480.0, slow = 720.0)
        val wayBeyond = PaceColors.colorFor(3600.0, fast = 480.0, slow = 720.0)
        assertEquals(slowest, wayBeyond)
    }

    @Test
    fun `a pace of nowhere gets the middle colour rather than a crash`() {
        val middle = PaceColors.colorFor(600.0, fast = 600.0, slow = 600.2)
        assertEquals(middle, PaceColors.colorFor(Double.NaN, fast = 480.0, slow = 720.0))
    }

    @Test
    fun `the ramp runs green through amber to red`() {
        val fast = PaceColors.colorFor(480.0, fast = 480.0, slow = 720.0)
        val mid = PaceColors.colorFor(600.0, fast = 480.0, slow = 720.0)
        val slow = PaceColors.colorFor(720.0, fast = 480.0, slow = 720.0)
        // Amber sits between the ends without being either of them; it is not a
        // midpoint in any single channel, which is the point of a three-stop ramp.
        assertTrue("the fast end should read green", green(fast) > red(fast))
        assertTrue("the slow end should read red", red(slow) > green(slow))
        assertTrue("green should drain away as pace slows", green(fast) > green(slow))
        assertTrue("red should build up as pace slows", red(slow) > red(fast))
        assertEquals(3, listOf(fast, mid, slow).distinct().size)
    }

    @Test
    fun `the legend gradient uses the same stops as the ramp`() {
        val stops = PaceColors.rampStops()
        assertEquals(3, stops.size)
        assertEquals(PaceColors.colorFor(480.0, fast = 480.0, slow = 720.0), stops.first())
        assertEquals(PaceColors.colorFor(720.0, fast = 480.0, slow = 720.0), stops.last())
    }

    private fun red(rgb: Int) = (rgb shr 16) and 0xFF
    private fun green(rgb: Int) = (rgb shr 8) and 0xFF
}
