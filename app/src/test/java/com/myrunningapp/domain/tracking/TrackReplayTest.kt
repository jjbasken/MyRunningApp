package com.myrunningapp.domain.tracking

import com.myrunningapp.domain.Units
import com.myrunningapp.domain.model.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class TrackReplayTest {

    private val start: Instant = Instant.parse("2026-09-01T06:00:00Z")

    /** Metres per degree of latitude on [Geo]'s sphere. */
    private val metersPerDegree = Math.toRadians(1.0) * 6_371_008.8

    /** One fix a second heading due north at [speed] m/s. */
    private fun segment(
        fromSecond: Long,
        seconds: Int,
        speed: Double = 3.0,
        fromMeters: Double = 0.0,
    ): List<GpsFix> = (0..seconds).map { i ->
        GpsFix(
            timestamp = start.plusSeconds(fromSecond + i),
            latitude = (fromMeters + i * speed) / metersPerDegree,
            longitude = 0.0,
            altitudeMeters = 0.0,
            accuracyMeters = 0f,
        )
    }

    @Test
    fun `one continuous segment becomes a run with its splits`() {
        // 2000 m in 667 s: one whole mile and a partial.
        val result = TrackReplay.replay(listOf(segment(0, 667)), ActivityType.RUN)!!

        assertEquals(2001.0, result.snapshot.distanceMeters, 0.5)
        assertEquals(667, result.snapshot.movingDurationSec)
        assertEquals(667, result.snapshot.elapsedDurationSec)
        assertEquals(start, result.startedAt)
        assertEquals(start.plusSeconds(667), result.endedAt)
        assertEquals(668, result.points.size)
        val splits = result.snapshot.completedSplits
        assertEquals(2, splits.size)
        assertEquals(Units.METERS_PER_MILE, splits[0].distanceMeters, 1e-9)
        assertEquals(536, splits[0].durationSec)
        assertEquals(2001.0 - Units.METERS_PER_MILE, splits[1].distanceMeters, 0.5)
    }

    @Test
    fun `the gap between segments is a pause`() {
        // 300 s, a ten-minute stop 500 m further on, then 300 s more.
        val result = TrackReplay.replay(
            listOf(segment(0, 300), segment(900, 300, fromMeters = 1400.0)),
            ActivityType.RUN,
        )!!

        // Neither the stop's time nor the 500 m jumped across it counts.
        assertEquals(1800.0, result.snapshot.distanceMeters, 0.5)
        assertEquals(600, result.snapshot.movingDurationSec)
        assertEquals(1200, result.snapshot.elapsedDurationSec)
        assertEquals(listOf(0, 1), result.points.map { it.segmentIndex }.distinct())
    }

    @Test
    fun `segments listed out of order are run in time order`() {
        val result = TrackReplay.replay(
            listOf(segment(900, 300, fromMeters = 1400.0), segment(0, 300)),
            ActivityType.RUN,
        )!!

        assertEquals(start, result.startedAt)
        assertEquals(600, result.snapshot.movingDurationSec)
        assertEquals(0, result.points.first().segmentIndex)
    }

    @Test
    fun `an overlapping segment does not count the overlap twice`() {
        val result = TrackReplay.replay(
            listOf(segment(0, 300), segment(200, 300, fromMeters = 600.0)),
            ActivityType.RUN,
        )!!

        // The second segment's first 100 s predate its resume and are dropped.
        assertEquals(500, result.snapshot.movingDurationSec)
    }

    @Test
    fun `the speed filter throws out a teleport`() {
        val fixes = segment(0, 100).toMutableList()
        fixes[50] = fixes[50].copy(latitude = fixes[50].latitude + 0.01) // ~1 km sideways for a second

        val result = TrackReplay.replay(listOf(fixes), ActivityType.RUN)!!

        assertEquals(300.0, result.snapshot.distanceMeters, 0.5)
        assertEquals(100, result.points.size)
    }

    @Test
    fun `a ride keeps the speeds a bike reaches`() {
        val fast = segment(0, 60, speed = 20.0)

        assertEquals(0.0, TrackReplay.replay(listOf(fast), ActivityType.RUN)?.snapshot?.distanceMeters ?: 0.0, 0.0)
        assertEquals(1200.0, TrackReplay.replay(listOf(fast), ActivityType.BIKE)!!.snapshot.distanceMeters, 0.5)
    }

    @Test
    fun `a track that goes nowhere is not an activity`() {
        assertNull(TrackReplay.replay(emptyList(), ActivityType.RUN))
        assertNull(TrackReplay.replay(listOf(segment(0, 0)), ActivityType.RUN))
        assertNull(TrackReplay.replay(listOf(segment(0, 60, speed = 0.0)), ActivityType.RUN))
    }
}
