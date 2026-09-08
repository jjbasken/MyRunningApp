package com.myrunningapp.data.location

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.RunSessionState
import com.myrunningapp.domain.tracking.GpsFix
import com.myrunningapp.domain.tracking.MileSplit
import com.myrunningapp.domain.tracking.RunSnapshot
import com.myrunningapp.domain.tracking.TrackedPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The tracker is the seam between the pure [com.myrunningapp.domain.tracking.RunSession]
 * and the database, so these tests are about *when* things are written: a run row
 * that only appears once tracking really starts, points batched rather than written
 * one at a time, and a summary that lands exactly once at the end.
 */
class RunTrackerTest {

    /** A clock the test winds forward by hand. */
    private class TestClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
    }

    /** Records what the tracker asked to be persisted, in order. */
    private class FakeRecorder : RunRecorder {
        var nextId = 7L
        val startedRuns = mutableListOf<Triple<ActivityType, Instant, Double>>()
        val pointBatches = mutableListOf<List<TrackedPoint>>()
        val splits = mutableListOf<MileSplit>()
        var finished: RunSnapshot? = null
        var finishedId: Long? = null
        var discarded: Long? = null

        val points: List<TrackedPoint> get() = pointBatches.flatten()

        override suspend fun startRun(
            activityType: ActivityType,
            startedAt: Instant,
            weightKg: Double,
        ): Long {
            startedRuns += Triple(activityType, startedAt, weightKg)
            return nextId
        }

        override suspend fun recordPoints(runId: Long, points: List<TrackedPoint>) {
            if (points.isNotEmpty()) pointBatches += points
        }

        override suspend fun recordSplit(runId: Long, split: MileSplit) {
            splits += split
        }

        override suspend fun finishRun(runId: Long, snapshot: RunSnapshot, endedAt: Instant) {
            finishedId = runId
            finished = snapshot
        }

        override suspend fun discardRun(runId: Long) {
            discarded = runId
        }
    }

    private val t0: Instant = Instant.parse("2026-09-08T10:00:00Z")
    private val clock = TestClock(t0)
    private val recorder = FakeRecorder()
    private val tracker = RunTracker(recorder, clock)

    private fun at(seconds: Long) { clock.now = t0.plusSeconds(seconds) }

    private fun fix(seconds: Long, metersNorth: Double) = GpsFix(
        timestamp = t0.plusSeconds(seconds),
        latitude = 51.5 + metersNorth / 111_195.0,
        longitude = -0.12,
        altitudeMeters = 20.0,
        accuracyMeters = 8f,
    )

    /** Runs `seconds` of a straight 3 m/s track, one fix a second. */
    private suspend fun runFor(seconds: Int, fromSecond: Long = 0) {
        for (i in 0..seconds) {
            val s = fromSecond + i
            at(s)
            tracker.onLocation(fix(s, (s - fromSecond) * 3.0))
        }
    }

    // --- the run row ---------------------------------------------------------

    @Test
    fun `starting a run without a countdown creates the run row straight away`() = runTest {
        tracker.start(ActivityType.RUN, countdownSeconds = 0, weightKg = 72.5)

        assertEquals(1, recorder.startedRuns.size)
        val (type, startedAt, weight) = recorder.startedRuns.single()
        assertEquals(ActivityType.RUN, type)
        assertEquals(t0, startedAt)
        assertEquals(72.5, weight, 0.0)
    }

    @Test
    fun `a countdown creates no run row until it reaches zero`() = runTest {
        tracker.start(ActivityType.RUN, countdownSeconds = 10, weightKg = 70.0)
        assertTrue(recorder.startedRuns.isEmpty())

        at(4)
        tracker.tick()
        assertTrue(recorder.startedRuns.isEmpty())

        at(10)
        tracker.tick()
        assertEquals(1, recorder.startedRuns.size)
    }

    @Test
    fun `cancelling a countdown leaves nothing behind`() = runTest {
        tracker.start(ActivityType.WALK, countdownSeconds = 30, weightKg = 70.0)
        at(5)
        tracker.cancel()

        assertTrue(recorder.startedRuns.isEmpty())
        assertNull(recorder.discarded)
        assertEquals(RunSessionState.IDLE, tracker.snapshot.value.state)
    }

    @Test
    fun `the tracker refuses to start a second run over a live one`() = runTest {
        tracker.start(ActivityType.RUN, countdownSeconds = 0, weightKg = 70.0)
        tracker.start(ActivityType.WALK, countdownSeconds = 0, weightKg = 70.0)

        assertEquals(1, recorder.startedRuns.size)
        assertEquals(ActivityType.RUN, tracker.snapshot.value.activityType)
    }

    // --- points --------------------------------------------------------------

    @Test
    fun `points are batched rather than written one per fix`() = runTest {
        tracker.start(ActivityType.RUN, countdownSeconds = 0, weightKg = 70.0)
        runFor(seconds = 4)

        assertTrue(
            "expected fewer batches than fixes, got ${recorder.pointBatches.size}",
            recorder.pointBatches.size < 4,
        )
    }

    @Test
    fun `a batch is flushed once the buffer has waited long enough`() = runTest {
        tracker.start(ActivityType.RUN, countdownSeconds = 0, weightKg = 70.0)
        runFor(seconds = 12)

        assertTrue("expected at least one flush", recorder.pointBatches.isNotEmpty())
        assertTrue(recorder.points.size >= 10)
    }

    @Test
    fun `finishing flushes every point still buffered`() = runTest {
        tracker.start(ActivityType.RUN, countdownSeconds = 0, weightKg = 70.0)
        runFor(seconds = 3)
        at(3)
        tracker.finish()

        assertEquals(4, recorder.points.size)
    }

    @Test
    fun `pausing flushes what has been recorded so far`() = runTest {
        tracker.start(ActivityType.RUN, countdownSeconds = 0, weightKg = 70.0)
        runFor(seconds = 3)
        at(3)
        tracker.pause()

        assertEquals(4, recorder.points.size)
    }

    @Test
    fun `points carry the segment they were recorded in`() = runTest {
        tracker.start(ActivityType.RUN, countdownSeconds = 0, weightKg = 70.0)
        runFor(seconds = 2)
        at(2)
        tracker.pause()
        at(60)
        tracker.resume()
        runFor(seconds = 2, fromSecond = 61)
        at(63)
        tracker.finish()

        assertEquals(setOf(0, 1), recorder.points.map { it.segmentIndex }.toSet())
    }

    @Test
    fun `points are written against the run they belong to`() = runTest {
        recorder.nextId = 42L
        tracker.start(ActivityType.RUN, countdownSeconds = 0, weightKg = 70.0)
        runFor(seconds = 3)
        at(3)
        tracker.finish()

        assertEquals(42L, recorder.finishedId)
    }

    // --- splits and the summary ----------------------------------------------

    @Test
    fun `each mile marker is persisted as it is reached`() = runTest {
        tracker.start(ActivityType.RUN, countdownSeconds = 0, weightKg = 70.0)
        // 3 m/s for 620 s covers a bit over a mile.
        runFor(seconds = 620)

        assertEquals(listOf(1), recorder.splits.map { it.splitNumber })
    }

    @Test
    fun `the partial last split is persisted at the finish`() = runTest {
        tracker.start(ActivityType.RUN, countdownSeconds = 0, weightKg = 70.0)
        runFor(seconds = 620)
        at(620)
        tracker.finish()

        assertEquals(listOf(1, 2), recorder.splits.map { it.splitNumber })
    }

    @Test
    fun `finishing writes the summary once`() = runTest {
        tracker.start(ActivityType.RUN, countdownSeconds = 0, weightKg = 70.0)
        runFor(seconds = 10)
        at(10)
        tracker.finish()

        val summary = checkNotNull(recorder.finished)
        assertEquals(RunSessionState.FINISHED, summary.state)
        assertEquals(30.0, summary.distanceMeters, 0.5)
        assertEquals(10L, summary.movingDurationSec)
    }

    @Test
    fun `a finished run leaves the tracker idle and ready for the next one`() = runTest {
        tracker.start(ActivityType.RUN, countdownSeconds = 0, weightKg = 70.0)
        runFor(seconds = 5)
        at(5)
        tracker.finish()

        assertEquals(RunSessionState.IDLE, tracker.snapshot.value.state)

        tracker.start(ActivityType.WALK, countdownSeconds = 0, weightKg = 70.0)
        assertEquals(RunSessionState.TRACKING, tracker.snapshot.value.state)
        assertEquals(2, recorder.startedRuns.size)
    }

    @Test
    fun `the last finished run can be found again for its detail screen`() = runTest {
        recorder.nextId = 99L
        tracker.start(ActivityType.RUN, countdownSeconds = 0, weightKg = 70.0)
        runFor(seconds = 5)
        at(5)
        tracker.finish()

        assertEquals(99L, tracker.lastFinishedRunId.value)
    }

    // --- live state ----------------------------------------------------------

    @Test
    fun `the snapshot follows the run as it happens`() = runTest {
        tracker.start(ActivityType.RUN, countdownSeconds = 0, weightKg = 70.0)
        runFor(seconds = 10)

        val live = tracker.snapshot.value
        assertEquals(RunSessionState.TRACKING, live.state)
        assertEquals(30.0, live.distanceMeters, 0.5)
    }

    @Test
    fun `ticking keeps the timer moving with no new fixes`() = runTest {
        tracker.start(ActivityType.RUN, countdownSeconds = 0, weightKg = 70.0)
        at(45)
        tracker.tick()

        assertEquals(45L, tracker.snapshot.value.movingDurationSec)
    }

    @Test
    fun `an idle tracker ignores pause resume and finish`() = runTest {
        tracker.pause()
        tracker.resume()
        tracker.finish()

        assertEquals(RunSessionState.IDLE, tracker.snapshot.value.state)
        assertTrue(recorder.startedRuns.isEmpty())
        assertNull(recorder.finished)
    }
}
