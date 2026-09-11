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
     *
     * A `segmentIndex` group holding a single fix (e.g. a pause landing right
     * after a resume) would produce a zero-length interval; Health Connect
     * rejects a zero-length `ExerciseSegment`, so those are dropped. If that
     * empties the result, the single-interval fallback used for the no-points
     * case takes over — but only when the run itself isn't zero-length, so this
     * never fabricates a degenerate interval either.
     */
    fun segments(run: Run, points: List<RunPoint>): List<HealthSegment> {
        val computed = if (points.isEmpty()) {
            emptyList()
        } else {
            points
                .groupBy { it.segmentIndex }
                .toSortedMap()
                .map { (_, segmentPoints) ->
                    HealthSegment(
                        startedAt = segmentPoints.minOf { it.timestamp },
                        endedAt = segmentPoints.maxOf { it.timestamp },
                    )
                }
                .filter { it.startedAt.isBefore(it.endedAt) }
        }
        return computed.ifEmpty {
            if (run.startedAt.isBefore(run.endedAt)) {
                listOf(HealthSegment(run.startedAt, run.endedAt))
            } else {
                emptyList()
            }
        }
    }

    /**
     * The instant reached after [movingOffsetSec] seconds of moving. Offsets past
     * the end of the run clamp to its last moment rather than running off into
     * the paused time that follows.
     *
     * Segments are contiguous with no representation of the pause between them,
     * so an offset landing exactly on a segment boundary resolves to the *next*
     * segment's start rather than the current one's end. That means a lap ending
     * at such a boundary absorbs the following pause into its wall-clock span —
     * `endTime - startTime` runs long even though its moving duration doesn't, so
     * a consumer deriving pace from timestamps rather than recorded
     * distance/duration would see that lap as artificially slow.
     */
    fun instantAt(segments: List<HealthSegment>, movingOffsetSec: Long): Instant {
        require(segments.isNotEmpty()) { "a workout needs at least one segment" }
        var remaining = movingOffsetSec.coerceAtLeast(0)
        for (segment in segments) {
            val length = segment.endedAt.epochSecond - segment.startedAt.epochSecond
            if (remaining < length) return segment.startedAt.plusSeconds(remaining)
            remaining -= length
        }
        return segments.last().endedAt
    }
}
