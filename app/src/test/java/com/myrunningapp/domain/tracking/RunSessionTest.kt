package com.myrunningapp.domain.tracking

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.RunSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Drives the session by hand, one instant at a time. Everything here is wall-clock
 * free — [RunSession] is told what time it is, so a whole run replays instantly.
 */
class RunSessionTest {

    private val t0: Instant = Instant.parse("2026-09-08T10:00:00Z")

    private fun at(seconds: Long): Instant = t0.plusSeconds(seconds)

    /** A fix `metersNorth` up the same meridian, taken `seconds` into the test. */
    private fun fix(seconds: Long, metersNorth: Double, accuracy: Float = 8f) = GpsFix(
        timestamp = at(seconds),
        latitude = 51.5 + metersNorth / 111_195.0,
        longitude = -0.12,
        altitudeMeters = 20.0,
        accuracyMeters = accuracy,
    )

    private fun session(countdownSeconds: Int = 0) =
        RunSession(activityType = ActivityType.RUN, countdownSeconds = countdownSeconds)

    // --- state machine -------------------------------------------------------

    @Test
    fun `a new session is idle and empty`() {
        val s = session().snapshot
        assertEquals(RunSessionState.IDLE, s.state)
        assertEquals(0.0, s.distanceMeters, 0.0)
        assertEquals(0L, s.movingDurationSec)
    }

    @Test
    fun `starting with no countdown goes straight to tracking`() {
        val session = session(countdownSeconds = 0)
        session.start(at(0))
        assertEquals(RunSessionState.TRACKING, session.snapshot.state)
        assertEquals(at(0), session.snapshot.startedAt)
    }

    @Test
    fun `starting with a countdown waits before tracking`() {
        val session = session(countdownSeconds = 30)
        session.start(at(0))
        assertEquals(RunSessionState.COUNTDOWN, session.snapshot.state)
        assertEquals(30, session.snapshot.countdownSecondsRemaining)
    }

    @Test
    fun `the countdown ticks down and then begins tracking`() {
        val session = session(countdownSeconds = 10)
        session.start(at(0))

        session.tick(at(4))
        assertEquals(6, session.snapshot.countdownSecondsRemaining)
        assertEquals(RunSessionState.COUNTDOWN, session.snapshot.state)

        session.tick(at(10))
        assertEquals(RunSessionState.TRACKING, session.snapshot.state)
        assertEquals(at(10), session.snapshot.startedAt)
    }

    @Test
    fun `cancelling a countdown returns to idle`() {
        val session = session(countdownSeconds = 30)
        session.start(at(0))
        session.cancel(at(3))
        assertEquals(RunSessionState.IDLE, session.snapshot.state)
    }

    @Test
    fun `skipping a countdown starts tracking immediately`() {
        val session = session(countdownSeconds = 30)
        session.start(at(0))
        session.skipCountdown(at(4))
        assertEquals(RunSessionState.TRACKING, session.snapshot.state)
        assertEquals(at(4), session.snapshot.startedAt)
    }

    @Test
    fun `finishing ends the session`() {
        val session = session()
        session.start(at(0))
        session.finish(at(60))
        assertEquals(RunSessionState.FINISHED, session.snapshot.state)
    }

    // --- distance ------------------------------------------------------------

    @Test
    fun `distance accumulates between accepted fixes`() {
        val session = session()
        session.start(at(0))
        session.onFix(fix(0, 0.0), at(0))
        session.onFix(fix(1, 3.0), at(1))
        session.onFix(fix(2, 6.0), at(2))
        assertEquals(6.0, session.snapshot.distanceMeters, 0.05)
    }

    @Test
    fun `the first fix establishes position without adding distance`() {
        val session = session()
        session.start(at(0))
        session.onFix(fix(0, 0.0), at(0))
        assertEquals(0.0, session.snapshot.distanceMeters, 0.0)
    }

    @Test
    fun `a rejected fix does not move the total`() {
        val session = session()
        session.start(at(0))
        session.onFix(fix(0, 0.0), at(0))
        session.onFix(fix(1, 3.0), at(1))
        session.onFix(fix(2, 6.0, accuracy = 90f), at(2))
        assertEquals(3.0, session.snapshot.distanceMeters, 0.05)
    }

    @Test
    fun `a rejected fix is reported with its reason`() {
        val session = session()
        session.start(at(0))
        session.onFix(fix(0, 0.0), at(0))
        val events = session.onFix(fix(1, 3.0, accuracy = 90f), at(1))
        assertEquals(
            listOf(RunSessionEvent.FixRejected(FixVerdict.POOR_ACCURACY)),
            events,
        )
    }

    @Test
    fun `fixes during the countdown establish position but add no distance`() {
        val session = session(countdownSeconds = 10)
        session.start(at(0))
        session.onFix(fix(0, 0.0), at(0))
        session.onFix(fix(1, 3.0), at(1))
        assertEquals(0.0, session.snapshot.distanceMeters, 0.0)

        session.tick(at(10))
        session.onFix(fix(11, 6.0), at(11))
        // Warm-up fixes never become distance, even the one just before zero.
        assertEquals(0.0, session.snapshot.distanceMeters, 0.0)
    }

    @Test
    fun `an accepted fix is emitted for persistence`() {
        val session = session()
        session.start(at(0))
        val events = session.onFix(fix(0, 0.0), at(0))
        val recorded = events.filterIsInstance<RunSessionEvent.PointRecorded>().single()
        assertEquals(at(0), recorded.point.fix.timestamp)
        assertEquals(0, recorded.point.segmentIndex)
    }

    // --- pause and resume ----------------------------------------------------

    @Test
    fun `no distance accrues while paused`() {
        val session = session()
        session.start(at(0))
        session.onFix(fix(0, 0.0), at(0))
        session.onFix(fix(1, 3.0), at(1))
        session.pause(at(2))
        session.onFix(fix(3, 30.0), at(3))
        assertEquals(3.0, session.snapshot.distanceMeters, 0.05)
    }

    @Test
    fun `resuming does not bridge the gap the user walked while paused`() {
        val session = session()
        session.start(at(0))
        session.onFix(fix(0, 0.0), at(0))
        session.onFix(fix(1, 3.0), at(1))
        session.pause(at(2))
        session.resume(at(60))
        session.onFix(fix(61, 100.0), at(61))
        session.onFix(fix(62, 103.0), at(62))
        // Only the 3 m before the pause and the 3 m after it count.
        assertEquals(6.0, session.snapshot.distanceMeters, 0.05)
    }

    @Test
    fun `resuming starts a new segment so the map can leave a gap`() {
        val session = session()
        session.start(at(0))
        session.onFix(fix(0, 0.0), at(0))
        assertEquals(0, session.snapshot.segmentIndex)

        session.pause(at(2))
        session.resume(at(60))
        assertEquals(1, session.snapshot.segmentIndex)

        val events = session.onFix(fix(61, 100.0), at(61))
        val recorded = events.filterIsInstance<RunSessionEvent.PointRecorded>().single()
        assertEquals(1, recorded.point.segmentIndex)
    }

    // --- timing --------------------------------------------------------------

    @Test
    fun `moving duration excludes the paused stretch`() {
        val session = session()
        session.start(at(0))
        session.pause(at(30))
        session.resume(at(90))
        session.finish(at(120))
        assertEquals(60L, session.snapshot.movingDurationSec)
    }

    @Test
    fun `elapsed duration includes the paused stretch`() {
        val session = session()
        session.start(at(0))
        session.pause(at(30))
        session.resume(at(90))
        session.finish(at(120))
        assertEquals(120L, session.snapshot.elapsedDurationSec)
    }

    @Test
    fun `moving duration keeps running between ticks while tracking`() {
        val session = session()
        session.start(at(0))
        session.tick(at(45))
        assertEquals(45L, session.snapshot.movingDurationSec)
    }

    @Test
    fun `the clock stops once the run is finished`() {
        val session = session()
        session.start(at(0))
        session.finish(at(100))
        session.tick(at(500))
        assertEquals(100L, session.snapshot.movingDurationSec)
        assertEquals(100L, session.snapshot.elapsedDurationSec)
    }

    @Test
    fun `average pace is moving time over distance in miles`() {
        val session = session()
        session.start(at(0))
        session.onFix(fix(0, 0.0), at(0))
        // Half a mile in exactly five minutes is a ten minute mile.
        session.onFix(fix(300, 804.672), at(300))
        session.finish(at(300))
        assertEquals(600.0, session.snapshot.avgPaceSecPerMile, 1.0)
    }

    @Test
    fun `average pace is not a number before the first metre`() {
        val session = session()
        session.start(at(0))
        session.tick(at(10))
        assertTrue(session.snapshot.avgPaceSecPerMile.isNaN())
    }
}
