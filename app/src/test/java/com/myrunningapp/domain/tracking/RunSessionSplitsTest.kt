package com.myrunningapp.domain.tracking

import com.myrunningapp.domain.Units
import com.myrunningapp.domain.model.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Mile splits, which have to be right to the second: they are both the numbers
 * spoken during the run and the numbers shown in the run's detail screen.
 */
class RunSessionSplitsTest {

    private val t0: Instant = Instant.parse("2026-09-08T10:00:00Z")
    private val mile = Units.METERS_PER_MILE

    private fun at(seconds: Long): Instant = t0.plusSeconds(seconds)

    private fun fix(seconds: Long, metersNorth: Double) = GpsFix(
        timestamp = at(seconds),
        latitude = 51.5 + metersNorth / 111_195.0,
        longitude = -0.12,
        altitudeMeters = 20.0,
        accuracyMeters = 8f,
    )

    private fun session() = RunSession(activityType = ActivityType.RUN)

    /** JUnit has no tolerant assert for Long, and split durations round to the second. */
    private fun assertNear(expected: Long, actual: Long, tolerance: Long) {
        assertTrue(
            "expected \u0024expected +/- \u0024tolerance but was \u0024actual",
            kotlin.math.abs(expected - actual) <= tolerance,
        )
    }

    /** Feeds a fix and returns only the split events it produced. */
    private fun RunSession.step(seconds: Long, metersNorth: Double): List<MileSplit> =
        onFix(fix(seconds, metersNorth), at(seconds))
            .filterIsInstance<RunSessionEvent.MileCompleted>()
            .map { it.split }

    @Test
    fun `crossing the first mile marker completes split one`() {
        val session = session()
        session.start(at(0))
        session.step(0, 0.0)
        assertTrue(session.step(1600, 1600.0).isEmpty())

        val splits = session.step(1700, 1700.0)
        assertEquals(1, splits.size)
        assertEquals(1, splits[0].splitNumber)
        assertEquals(mile, splits[0].distanceMeters, 0.01)
    }

    @Test
    fun `the split is timed at the interpolated crossing, not at the next fix`() {
        val session = session()
        session.start(at(0))
        session.step(0, 0.0)
        session.step(1600, 1600.0)

        // Running at 1 m/s, the mile falls 9.3 s into the leg from 1600 m to 1700 m.
        val split = session.step(1700, 1700.0).single()
        assertNear(1609L, split.durationSec, 2L)
    }

    @Test
    fun `a full mile split paces at its own duration`() {
        val session = session()
        session.start(at(0))
        session.step(0, 0.0)
        session.step(1600, 1600.0)

        val split = session.step(1700, 1700.0).single()
        assertEquals(split.durationSec.toDouble(), split.paceSecPerMile, 1.0)
    }

    @Test
    fun `one long leg can complete several miles at once`() {
        val session = session()
        session.start(at(0))
        session.step(0, 0.0)

        // 4000 m in 400 s — sparse fixes, but a plausible 10 m per second.
        val splits = session.step(400, 4000.0)
        assertEquals(listOf(1, 2), splits.map { it.splitNumber })
        assertNear(161L, splits[0].durationSec, 2L)
        assertNear(161L, splits[1].durationSec, 2L)
    }

    @Test
    fun `split boundaries do not drift over successive miles`() {
        val session = session()
        session.start(at(0))
        session.step(0, 0.0)
        // A steady 2 m/s for three miles: every split should be the same length.
        var seconds = 0L
        var meters = 0.0
        val splits = mutableListOf<MileSplit>()
        repeat(30) {
            seconds += 100
            meters += 200.0
            splits += session.step(seconds, meters)
        }
        assertEquals(listOf(1, 2, 3), splits.map { it.splitNumber })
        splits.forEach { assertNear(805L, it.durationSec, 2L) }
    }

    @Test
    fun `time spent paused is not charged to the split`() {
        val session = session()
        session.start(at(0))
        session.step(0, 0.0)
        session.step(800, 800.0)

        session.pause(at(800))
        session.resume(at(1400))
        // A new segment: the first fix after resuming only re-anchors the track.
        session.step(1400, 800.0)

        val split = session.step(2210, 1610.0).single()
        assertNear(1609L, split.durationSec, 3L)
    }

    @Test
    fun `finishing mid-mile completes a partial final split`() {
        val session = session()
        session.start(at(0))
        session.step(0, 0.0)
        session.step(1700, 1700.0) // mile 1 completed here

        val events = session.finish(at(1700))
        val split = events.filterIsInstance<RunSessionEvent.FinalSplitCompleted>()
            .single().split
        assertEquals(2, split.splitNumber)
        assertEquals(1700.0 - mile, split.distanceMeters, 1.0)
    }

    @Test
    fun `a partial final split projects its pace out to a full mile`() {
        val session = session()
        session.start(at(0))
        session.step(0, 0.0)
        session.step(1700, 1700.0)

        val split = session.finish(at(1700))
            .filterIsInstance<RunSessionEvent.FinalSplitCompleted>().single().split
        // 90.7 m in 90.7 s at 1 m/s still projects to a 1609 s mile.
        assertEquals(1609.0, split.paceSecPerMile, 30.0)
    }

    @Test
    fun `finishing exactly on a mile marker adds no stub split`() {
        val session = session()
        session.start(at(0))
        session.step(0, 0.0)
        session.step(1609, 1609.344)

        val events = session.finish(at(1609))
        assertTrue(events.filterIsInstance<RunSessionEvent.FinalSplitCompleted>().isEmpty())
    }

    @Test
    fun `a run shorter than a mile is one partial split`() {
        val session = session()
        session.start(at(0))
        session.step(0, 0.0)
        session.step(400, 400.0)

        val split = session.finish(at(400))
            .filterIsInstance<RunSessionEvent.FinalSplitCompleted>().single().split
        assertEquals(1, split.splitNumber)
        assertEquals(400.0, split.distanceMeters, 1.0)
    }

    @Test
    fun `the snapshot carries every split completed so far`() {
        val session = session()
        session.start(at(0))
        session.step(0, 0.0)
        session.step(400, 4000.0)
        assertEquals(listOf(1, 2), session.snapshot.completedSplits.map { it.splitNumber })

        session.finish(at(400))
        assertEquals(listOf(1, 2, 3), session.snapshot.completedSplits.map { it.splitNumber })
    }
}
