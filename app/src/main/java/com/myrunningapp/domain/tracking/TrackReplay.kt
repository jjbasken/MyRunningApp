package com.myrunningapp.domain.tracking

import com.myrunningapp.domain.model.ActivityType
import java.time.Instant

/**
 * Plays a track recorded elsewhere through a real [RunSession], as if it were
 * being run now.
 *
 * Going through the session rather than summing legs directly means an
 * imported activity gets its distance, moving time and mile splits from exactly
 * the rules a recorded one does — the same speed filter throws out the same
 * teleports, and the splits are interpolated the same way.
 *
 * Each segment is one stretch of tracking; the gap between segments is a pause.
 * A file that never breaks its track into segments (Strava's usually do not)
 * therefore counts every second from start to finish as moving time.
 */
object TrackReplay {

    class Result(
        val snapshot: RunSnapshot,
        /** The points the session kept, tagged with the segment they belong to. */
        val points: List<TrackedPoint>,
        val startedAt: Instant,
        val endedAt: Instant,
    )

    /** @return null when nothing in [segments] survives to cover any distance. */
    fun replay(segments: List<List<GpsFix>>, activityType: ActivityType): Result? {
        // Segments are taken in the order they were run, whatever order the file lists them in.
        val ordered = segments.filter { it.isNotEmpty() }.sortedBy { it.first().timestamp }
        if (ordered.isEmpty()) return null

        val session = RunSession(activityType = activityType)
        val points = mutableListOf<TrackedPoint>()
        val startedAt = ordered.first().first().timestamp
        var clock = startedAt

        session.start(startedAt)
        ordered.forEachIndexed { index, segment ->
            if (index > 0) {
                // A segment that starts before the last one ended overlaps it;
                // resuming at the overlap would count that stretch twice.
                clock = maxOf(clock, segment.first().timestamp)
                session.resume(clock)
            }
            segment.forEach { fix ->
                session.onFix(fix, now = fix.timestamp).forEach { event ->
                    if (event is RunSessionEvent.PointRecorded) points += event.point
                }
                clock = maxOf(clock, fix.timestamp)
            }
            session.pause(clock)
        }
        session.finish(clock)

        val snapshot = session.snapshot
        if (points.size < 2 || snapshot.distanceMeters <= 0.0) return null
        return Result(snapshot, points, startedAt = startedAt, endedAt = clock)
    }
}
