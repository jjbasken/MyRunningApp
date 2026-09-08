package com.myrunningapp.domain.tracking

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.RunSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RunSessionTimingTest {
    private val wall = Instant.parse("2026-09-08T10:00:00Z")
    private val origin = 100_000L

    private fun fix(seconds: Long, meters: Double, correction: Long = 0) = GpsFix(
        timestamp = wall.plusSeconds(seconds + correction),
        latitude = 51.5 + meters / 111_195.0,
        longitude = -0.12, altitudeMeters = 0.0, accuracyMeters = 5f,
        elapsedRealtimeMillis = origin + seconds * 1000,
    )

    @Test
    fun `cached fix before start cannot add pre-run distance`() {
        val session = RunSession(ActivityType.RUN)
        session.start(wall, origin)
        val rejected = session.onFix(fix(-4, 0.0), wall, origin)
        assertEquals(listOf(RunSessionEvent.FixRejected(FixVerdict.BEFORE_TRACKING)), rejected)
        val accepted = session.onFix(fix(0, 40.0), wall, origin)
        assertEquals(1, accepted.filterIsInstance<RunSessionEvent.PointRecorded>().size)
        assertEquals(0.0, session.snapshot.distanceMeters, 0.0)
    }

    @Test
    fun `batched fix from pause cannot become the resumed route anchor`() {
        val session = RunSession(ActivityType.RUN)
        session.start(wall, origin)
        session.onFix(fix(0, 0.0), wall, origin)
        session.pause(wall.plusSeconds(10), origin + 10_000)
        session.resume(wall.plusSeconds(20), origin + 20_000)
        val rejected = session.onFix(fix(19, 100.0), wall.plusSeconds(20), origin + 20_000)
        assertEquals(listOf(RunSessionEvent.FixRejected(FixVerdict.BEFORE_TRACKING)), rejected)
        session.onFix(fix(20, 110.0), wall.plusSeconds(20), origin + 20_000)
        session.onFix(fix(21, 113.0), wall.plusSeconds(21), origin + 21_000)
        assertEquals(3.0, session.snapshot.distanceMeters, 0.01)
        assertEquals(11L, session.snapshot.movingDurationSec)
        assertEquals(1, session.snapshot.segmentIndex)
    }

    @Test
    fun `countdown uses elapsed time and rejects delayed warmup fixes`() {
        val session = RunSession(ActivityType.RUN, countdownSeconds = 3)
        session.start(wall, origin)
        session.tick(wall.minusSeconds(3600), origin + 2000)
        assertEquals(1, session.snapshot.countdownSecondsRemaining)
        session.tick(wall.plusSeconds(3600), origin + 3000)
        assertEquals(RunSessionState.TRACKING, session.snapshot.state)
        assertEquals(wall.plusSeconds(3), session.snapshot.startedAt)
        val rejected = session.onFix(fix(2, 0.0), wall.plusSeconds(3600), origin + 3000)
        assertEquals(listOf(RunSessionEvent.FixRejected(FixVerdict.BEFORE_TRACKING)), rejected)
        session.onFix(fix(3, 10.0), wall.plusSeconds(3600), origin + 3000)
        assertEquals(0.0, session.snapshot.distanceMeters, 0.0)
    }

    @Test
    fun `clock corrections do not change route order pace or mile splits`() {
        val stable = RunSession(ActivityType.RUN)
        val adjusted = RunSession(ActivityType.RUN)
        stable.start(wall, origin)
        adjusted.start(wall, origin)
        for (second in 0L..600L) {
            val correction = when {
                second < 100 -> 0L
                second < 300 -> -3600L
                else -> 3600L
            }
            stable.onFix(fix(second, second * 3.0), wall.plusSeconds(second), origin + second * 1000)
            val events = adjusted.onFix(fix(second, second * 3.0, correction),
                wall.plusSeconds(second + correction), origin + second * 1000)
            val point = events.filterIsInstance<RunSessionEvent.PointRecorded>().single()
            assertEquals(wall.plusSeconds(second), point.point.fix.timestamp)
        }
        stable.finish(wall.plusSeconds(600), origin + 600_000)
        adjusted.finish(wall.minusSeconds(3000), origin + 600_000)
        assertEquals(stable.snapshot, adjusted.snapshot)
        assertEquals(600L, adjusted.snapshot.movingDurationSec)
        assertEquals(wall.plusSeconds(600), adjusted.timestamp)
        assertTrue(adjusted.snapshot.completedSplits.size >= 2)
    }

    @Test
    fun `pause and resume exclude paused time across clock corrections`() {
        val session = RunSession(ActivityType.RUN)
        session.start(wall, origin)
        session.pause(wall.minusSeconds(3600), origin + 10_000)
        session.resume(wall.plusSeconds(3600), origin + 30_000)
        session.finish(wall.minusSeconds(7200), origin + 40_000)
        assertEquals(20L, session.snapshot.movingDurationSec)
        assertEquals(40L, session.snapshot.elapsedDurationSec)
    }
}
