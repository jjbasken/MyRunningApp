package com.myrunningapp.domain.tracking

import com.myrunningapp.domain.model.ActivityType
import java.time.Instant

/**
 * Loads a recorded GPS trace from `src/test/resources/traces`.
 *
 * The format is one fix per line — `timestamp,latitude,longitude,altitude,accuracy`
 * — with `#` comments and a header line. It is deliberately trivial to hand-edit,
 * so a glitch can be dropped into a trace to see what the filter does with it.
 */
object GpsTrace {

    fun load(resourceName: String): List<GpsFix> {
        val stream = checkNotNull(
            GpsTrace::class.java.classLoader?.getResourceAsStream(resourceName),
        ) { "no such trace resource: $resourceName" }

        return stream.bufferedReader().useLines { lines ->
            lines
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("timestamp") }
                .map(::parse)
                .toList()
        }
    }

    private fun parse(line: String): GpsFix {
        val parts = line.split(',')
        require(parts.size == 5) { "malformed trace line: $line" }
        return GpsFix(
            timestamp = Instant.parse(parts[0]),
            latitude = parts[1].toDouble(),
            longitude = parts[2].toDouble(),
            altitudeMeters = parts[3].toDouble(),
            accuracyMeters = parts[4].toFloat(),
        )
    }
}

/** What a replay produced — the finished run plus everything the session reported. */
class ReplayResult(
    val snapshot: RunSnapshot,
    val events: List<RunSessionEvent>,
) {
    val recordedPoints: List<TrackedPoint>
        get() = events.filterIsInstance<RunSessionEvent.PointRecorded>().map { it.point }

    val acceptedCount: Int get() = recordedPoints.size

    val rejected: List<FixVerdict>
        get() = events.filterIsInstance<RunSessionEvent.FixRejected>().map { it.verdict }

    /** Whole-mile splits only; the partial last stretch is [finalSplit]. */
    val mileSplits: List<MileSplit>
        get() = events.filterIsInstance<RunSessionEvent.MileCompleted>().map { it.split }

    val finalSplit: MileSplit?
        get() = events.filterIsInstance<RunSessionEvent.FinalSplitCompleted>()
            .singleOrNull()?.split
}

/**
 * Feeds a trace through a real [RunSession] at replay speed.
 *
 * Every fix is handled at the instant it was taken, so the staleness rule sees
 * the same thing it would on a phone keeping up with its GPS.
 */
object GpsReplay {

    fun run(
        fixes: List<GpsFix>,
        activityType: ActivityType = ActivityType.RUN,
        countdownSeconds: Int = 0,
    ): ReplayResult {
        require(fixes.isNotEmpty()) { "cannot replay an empty trace" }

        val session = RunSession(
            activityType = activityType,
            countdownSeconds = countdownSeconds,
        )
        val events = mutableListOf<RunSessionEvent>()

        events += session.start(fixes.first().timestamp)
        fixes.forEach { fix ->
            events += session.tick(fix.timestamp)
            events += session.onFix(fix, now = fix.timestamp)
        }
        events += session.finish(fixes.last().timestamp)

        return ReplayResult(session.snapshot, events)
    }
}
