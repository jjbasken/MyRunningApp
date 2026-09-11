package com.myrunningapp.domain.health

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthSegment
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.RunPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class HealthTimelineTest {

    private val t0: Instant = Instant.parse("2026-09-09T12:00:00Z")

    private fun point(offsetSec: Long, segmentIndex: Int) = RunPoint(
        id = 0, runId = 1, timestamp = t0.plusSeconds(offsetSec),
        latitude = 40.0, longitude = -105.0, altitudeMeters = 1600.0,
        accuracyMeters = 5f, segmentIndex = segmentIndex,
    )

    private fun run(endOffsetSec: Long) = Run(
        id = 1, startedAt = t0, endedAt = t0.plusSeconds(endOffsetSec),
        activityType = ActivityType.RUN, distanceMeters = 1000.0,
        movingDurationSec = 300, elapsedDurationSec = endOffsetSec,
        avgPaceSecPerMile = 480.0, calories = 100, weightKgAtRun = 70.0,
    )

    @Test
    fun `one segment spans the whole run when it was never paused`() {
        val segments = HealthTimeline.segments(run(300), listOf(point(0, 0), point(300, 0)))

        assertEquals(listOf(HealthSegment(t0, t0.plusSeconds(300))), segments)
    }

    @Test
    fun `a pause splits the run into two segments with a gap between them`() {
        val points = listOf(point(0, 0), point(100, 0), point(400, 1), point(500, 1))

        val segments = HealthTimeline.segments(run(500), points)

        assertEquals(
            listOf(
                HealthSegment(t0, t0.plusSeconds(100)),
                HealthSegment(t0.plusSeconds(400), t0.plusSeconds(500)),
            ),
            segments,
        )
    }

    @Test
    fun `a run with no points still reports one segment covering it`() {
        val segments = HealthTimeline.segments(run(300), emptyList())

        assertEquals(listOf(HealthSegment(t0, t0.plusSeconds(300))), segments)
    }

    @Test
    fun `a moving offset inside the first segment is that many seconds after the start`() {
        val segments = listOf(
            HealthSegment(t0, t0.plusSeconds(100)),
            HealthSegment(t0.plusSeconds(400), t0.plusSeconds(500)),
        )

        assertEquals(t0.plusSeconds(60), HealthTimeline.instantAt(segments, 60))
    }

    @Test
    fun `a moving offset past the first segment skips the paused stretch`() {
        val segments = listOf(
            HealthSegment(t0, t0.plusSeconds(100)),
            HealthSegment(t0.plusSeconds(400), t0.plusSeconds(500)),
        )

        // 100 s of moving time happened before the pause; 30 s more lands 30 s
        // into the second segment, not 130 s after the start.
        assertEquals(t0.plusSeconds(430), HealthTimeline.instantAt(segments, 130))
    }

    @Test
    fun `an offset beyond all moving time clamps to the last segment's end`() {
        val segments = listOf(HealthSegment(t0, t0.plusSeconds(100)))

        assertEquals(t0.plusSeconds(100), HealthTimeline.instantAt(segments, 9_999))
    }

    @Test
    fun `an offset exactly on a segment boundary returns the next segment's start`() {
        val segments = listOf(
            HealthSegment(t0, t0.plusSeconds(100)),
            HealthSegment(t0.plusSeconds(400), t0.plusSeconds(500)),
        )

        assertEquals(t0.plusSeconds(400), HealthTimeline.instantAt(segments, 100))
    }

    @Test
    fun `a segment index holding a single fix is dropped as zero-length`() {
        val points = listOf(point(0, 0), point(100, 0), point(200, 1))

        val segments = HealthTimeline.segments(run(300), points)

        assertEquals(listOf(HealthSegment(t0, t0.plusSeconds(100))), segments)
    }

    @Test
    fun `segments falls back to the whole run when every segment is degenerate`() {
        val points = listOf(point(50, 0), point(150, 1))

        val segments = HealthTimeline.segments(run(300), points)

        assertEquals(listOf(HealthSegment(t0, t0.plusSeconds(300))), segments)
    }
}
