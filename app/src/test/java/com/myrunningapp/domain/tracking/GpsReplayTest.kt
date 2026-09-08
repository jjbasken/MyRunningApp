package com.myrunningapp.domain.tracking

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.RunSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The design's replay harness: a recorded trace goes through the real pipeline,
 * so a route of known length can be checked without going outside.
 */
class GpsReplayTest {

    @Test
    fun `the loop measures its surveyed length`() {
        val result = GpsReplay.run(GpsTrace.load("traces/park_loop_3240m.csv"))

        // Two laps of a 405 m square: 3240 m of ground truth, and the design
        // allows the pipeline 2% either way.
        assertEquals(3240.0, result.snapshot.distanceMeters, 3240.0 * 0.02)
    }

    @Test
    fun `a two lap loop produces two mile splits`() {
        val result = GpsReplay.run(GpsTrace.load("traces/park_loop_3240m.csv"))
        assertEquals(listOf(1, 2), result.mileSplits.map { it.splitNumber })
    }

    @Test
    fun `the loop is run at a steady pace so both splits match`() {
        val result = GpsReplay.run(GpsTrace.load("traces/park_loop_3240m.csv"))
        val (first, second) = result.mileSplits
        assertTrue(
            "splits drifted: ${first.durationSec}s then ${second.durationSec}s",
            kotlin.math.abs(first.durationSec - second.durationSec) <= 2,
        )
    }

    @Test
    fun `every accepted fix on the loop is recorded for the map`() {
        val result = GpsReplay.run(GpsTrace.load("traces/park_loop_3240m.csv"))
        assertEquals(result.acceptedCount, result.recordedPoints.size)
        assertTrue(result.recordedPoints.all { it.segmentIndex == 0 })
    }

    @Test
    fun `glitches in a city trace are thrown away`() {
        val result = GpsReplay.run(GpsTrace.load("traces/city_1700m_glitches.csv"))
        assertTrue("expected the filter to reject some fixes", result.rejected.isNotEmpty())
    }

    @Test
    fun `throwing the glitches away leaves the surveyed distance intact`() {
        val result = GpsReplay.run(GpsTrace.load("traces/city_1700m_glitches.csv"))
        assertEquals(1701.0, result.snapshot.distanceMeters, 1701.0 * 0.02)
    }

    @Test
    fun `a run past one mile is one mile split and a partial one`() {
        val result = GpsReplay.run(GpsTrace.load("traces/city_1700m_glitches.csv"))
        assertEquals(listOf(1), result.mileSplits.map { it.splitNumber })
        assertEquals(2, checkNotNull(result.finalSplit).splitNumber)
    }

    @Test
    fun `a teleport and a wide fix are both rejected for their own reasons`() {
        val result = GpsReplay.run(GpsTrace.load("traces/city_1700m_glitches.csv"))
        assertTrue(result.rejected.contains(FixVerdict.IMPLAUSIBLE_SPEED))
        assertTrue(result.rejected.contains(FixVerdict.POOR_ACCURACY))
        assertTrue(result.rejected.contains(FixVerdict.OUT_OF_ORDER))
    }

    @Test
    fun `replaying a trace leaves the session finished`() {
        val result = GpsReplay.run(GpsTrace.load("traces/park_loop_3240m.csv"))
        assertEquals(RunSessionState.FINISHED, result.snapshot.state)
    }

    @Test
    fun `a walk replays the same way a run does`() {
        val result = GpsReplay.run(
            GpsTrace.load("traces/park_loop_3240m.csv"),
            activityType = ActivityType.WALK,
        )
        assertEquals(ActivityType.WALK, result.snapshot.activityType)
    }
}
