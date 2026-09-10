package com.myrunningapp.domain.health

import com.myrunningapp.domain.model.HealthSegment
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.RunPoint
import java.time.Instant

/**
 * Turns a run's pause structure into wall-clock intervals, and maps moving-time
 * offsets onto them.
 *
 * Splits record how long a mile took to *run*, with no timestamps; Health
 * Connect wants laps on the wall clock, inside the session and clear of the
 * pauses. Walking the segments is what reconciles the two — a mile that finished
 * 130 s into the moving time of a run that was paused at 100 s ends 30 s into the
 * second segment, not 130 s after the start.
 */
object HealthTimeline {

    /**
     * One interval per `segmentIndex`, from its first fix to its last. A run with
     * no points (possible if GPS never delivered) still gets one interval, so a
     * session is never built with nothing inside it.
     */
    fun segments(run: Run, points: List<RunPoint>): List<HealthSegment> {
        if (points.isEmpty()) return listOf(HealthSegment(run.startedAt, run.endedAt))
        return points
            .groupBy { it.segmentIndex }
            .toSortedMap()
            .map { (_, segmentPoints) ->
                HealthSegment(
                    startedAt = segmentPoints.minOf { it.timestamp },
                    endedAt = segmentPoints.maxOf { it.timestamp },
                )
            }
    }

    /**
     * The instant reached after [movingOffsetSec] seconds of moving. Offsets past
     * the end of the run clamp to its last moment rather than running off into
     * the paused time that follows.
     */
    fun instantAt(segments: List<HealthSegment>, movingOffsetSec: Long): Instant {
        require(segments.isNotEmpty()) { "a workout needs at least one segment" }
        var remaining = movingOffsetSec.coerceAtLeast(0)
        for ((index, segment) in segments.withIndex()) {
            val length = segment.endedAt.epochSecond - segment.startedAt.epochSecond
            if (remaining < length) return segment.startedAt.plusSeconds(remaining)
            remaining -= length
            if (remaining == 0L) {
                // If we've exactly consumed this segment and there are more,
                // move to the next segment's start to skip any pause.
                if (index < segments.size - 1) return segments[index + 1].startedAt
                return segment.endedAt
            }
        }
        return segments.last().endedAt
    }
}
