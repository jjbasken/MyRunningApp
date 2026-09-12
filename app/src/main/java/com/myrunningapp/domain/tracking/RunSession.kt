package com.myrunningapp.domain.tracking

import com.myrunningapp.domain.Units
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.RunSessionState
import java.time.Instant
import kotlin.math.ceil

/**
 * The authoritative state of one run in progress.
 *
 * ```
 * IDLE ──start──▶ COUNTDOWN ──▶ TRACKING ⇄ PAUSED ──finish──▶ FINISHED
 *                    │
 *                    └──cancel──▶ IDLE
 * ```
 *
 * Deliberately free of Android, coroutines and clocks: every method is told what
 * wall time and milliseconds since boot (including sleep). Defaults support deterministic
 * timestamped replays; Android callers always supply monotonic time. Thus an entire run — including its mile splits — replays in a unit
 * test in microseconds. `LocationTrackingService` owns an instance, feeds it
 * fixes and ticks, and turns the returned [RunSessionEvent]s into database writes
 * and announcements.
 *
 * Not thread-safe; the service confines it to a single coroutine.
 */
class RunSession(
    val activityType: ActivityType,
    private val countdownSeconds: Int = 0,
    private val filter: GpsFilter = GpsFilter.forActivity(activityType),
) {

    private var state: RunSessionState = RunSessionState.IDLE
    private var currentElapsedMillis: Long? = null
    private var clockOriginMillis: Long = 0
    private var clockOriginWall: Instant = Instant.EPOCH
    private var startedElapsedMillis: Long? = null

    /** A stable wall-time timeline anchored once, so exports stay ordered after clock changes. */
    val timestamp: Instant?
        get() = currentElapsedMillis?.let(::wallTimeAt)

    private fun wallTimeAt(elapsedMillis: Long): Instant =
        clockOriginWall.plusMillis(elapsedMillis - clockOriginMillis)

    private var countdownEndsMillis: Long? = null
    private var countdownRemaining: Int = 0

    private var startedAt: Instant? = null
    private var endedElapsedMillis: Long? = null

    /** Moving time from tracking windows that have already closed. */
    private var movingMillisBanked: Long = 0
    /** When the current tracking window opened, or null if not tracking. */
    private var trackingSinceMillis: Long? = null

    private var distanceMeters: Double = 0.0
    private var segmentIndex: Int = 0

    /**
     * Baseline for both the filter and distance. Cleared whenever the track breaks
     * (tracking starts, or a pause ends) so a gap is never counted as running.
     */
    private var anchorFix: GpsFix? = null
    /** Last fix accepted at any point, kept only so the UI can show a position. */
    private var lastFix: GpsFix? = null
    /** Moving time when [anchorFix] was taken, the left edge of split interpolation. */
    private var movingMillisAtAnchor: Long = 0

    private val splits = mutableListOf<MileSplit>()
    /** Moving time at the last mile marker, so each split is measured from the last. */
    private var movingMillisAtLastMarker: Long = 0

    val snapshot: RunSnapshot
        get() {
            val movingSec = movingMillis() / 1000
            return RunSnapshot(
                state = state,
                activityType = activityType,
                startedAt = startedAt,
                distanceMeters = distanceMeters,
                movingDurationSec = movingSec,
                elapsedDurationSec = elapsedMillis() / 1000,
                avgPaceSecPerMile = Units.paceSecPerMile(distanceMeters, movingSec),
                segmentIndex = segmentIndex,
                countdownSecondsRemaining = countdownRemaining,
                lastFix = lastFix,
                completedSplits = splits.toList(),
            )
        }

    // --- commands ------------------------------------------------------------

    /** Begins the countdown, or tracking straight away when the countdown is off. */
    fun start(now: Instant, elapsedRealtimeMillis: Long = now.toEpochMilli()): List<RunSessionEvent> {
        if (state != RunSessionState.IDLE) return emptyList()
        clockOriginMillis = elapsedRealtimeMillis
        clockOriginWall = now
        currentElapsedMillis = elapsedRealtimeMillis
        return if (countdownSeconds > 0) {
            state = RunSessionState.COUNTDOWN
            countdownEndsMillis = elapsedRealtimeMillis + countdownSeconds * 1000L
            countdownRemaining = countdownSeconds
            listOf(RunSessionEvent.CountdownTick(countdownSeconds))
        } else {
            listOf(beginTracking(elapsedRealtimeMillis))
        }
    }

    /** "Start now" during a countdown. */
    fun skipCountdown(now: Instant, elapsedRealtimeMillis: Long = now.toEpochMilli()): List<RunSessionEvent> {
        if (state != RunSessionState.COUNTDOWN) return emptyList()
        currentElapsedMillis = elapsedRealtimeMillis
        return listOf(beginTracking(elapsedRealtimeMillis))
    }

    /** Abandons a countdown before it reaches zero; nothing was recorded. */
    fun cancel(now: Instant, elapsedRealtimeMillis: Long = now.toEpochMilli()) {
        if (state != RunSessionState.COUNTDOWN) return
        currentElapsedMillis = elapsedRealtimeMillis
        state = RunSessionState.IDLE
        countdownEndsMillis = null
        countdownRemaining = 0
        anchorFix = null
        lastFix = null
        splits.clear()
        movingMillisAtLastMarker = 0
    }

    fun pause(now: Instant, elapsedRealtimeMillis: Long = now.toEpochMilli()) {
        if (state != RunSessionState.TRACKING) return
        currentElapsedMillis = elapsedRealtimeMillis
        closeTrackingWindow(elapsedRealtimeMillis)
        state = RunSessionState.PAUSED
        anchorFix = null
    }

    fun resume(now: Instant, elapsedRealtimeMillis: Long = now.toEpochMilli()) {
        if (state != RunSessionState.PAUSED) return
        currentElapsedMillis = elapsedRealtimeMillis
        state = RunSessionState.TRACKING
        trackingSinceMillis = elapsedRealtimeMillis
        segmentIndex++
    }

    /** Ends the run for good. */
    fun finish(now: Instant, elapsedRealtimeMillis: Long = now.toEpochMilli()): List<RunSessionEvent> {
        if (state != RunSessionState.TRACKING && state != RunSessionState.PAUSED) {
            return emptyList()
        }
        currentElapsedMillis = elapsedRealtimeMillis
        closeTrackingWindow(elapsedRealtimeMillis)
        endedElapsedMillis = elapsedRealtimeMillis
        state = RunSessionState.FINISHED
        anchorFix = null
        return closeFinalSplit()
    }

    /**
     * Advances the clock without a new fix — drives the countdown and keeps the
     * on-screen timer moving between GPS updates.
     */
    fun tick(now: Instant, elapsedRealtimeMillis: Long = now.toEpochMilli()): List<RunSessionEvent> {
        if (!snapshot.isActive) return emptyList()
        currentElapsedMillis = elapsedRealtimeMillis

        if (state != RunSessionState.COUNTDOWN) return emptyList()

        val endsAt = countdownEndsMillis ?: return emptyList()
        if (elapsedRealtimeMillis >= endsAt) {
            countdownRemaining = 0
            return listOf(RunSessionEvent.CountdownTick(0), beginTracking(endsAt))
        }

        val remaining = ceil((endsAt - elapsedRealtimeMillis) / 1000.0).toInt()
        if (remaining == countdownRemaining) return emptyList()
        countdownRemaining = remaining
        return listOf(RunSessionEvent.CountdownTick(remaining))
    }

    /** Offers a new location reading to the run. */
    fun onFix(fix: GpsFix, now: Instant, elapsedRealtimeMillis: Long = now.toEpochMilli()): List<RunSessionEvent> {
        if (state != RunSessionState.COUNTDOWN && state != RunSessionState.TRACKING) {
            return emptyList()
        }
        currentElapsedMillis = elapsedRealtimeMillis

        if (state == RunSessionState.TRACKING && fix.elapsedRealtimeMillis < checkNotNull(trackingSinceMillis)) {
            return listOf(RunSessionEvent.FixRejected(FixVerdict.BEFORE_TRACKING))
        }
        val verdict = filter.evaluate(fix, previous = anchorFix, now = now,
            elapsedRealtimeMillis = elapsedRealtimeMillis)
        if (verdict != FixVerdict.ACCEPTED) {
            return listOf(RunSessionEvent.FixRejected(verdict))
        }
        val timedFix = fix.copy(timestamp = wallTimeAt(fix.elapsedRealtimeMillis))
        lastFix = timedFix

        // Warm-up fixes only prove the GPS has a lock; they are not part of the run.
        if (state == RunSessionState.COUNTDOWN) return emptyList()

        val previous = anchorFix
        val movingBefore = movingMillisAtAnchor
        val distanceBefore = distanceMeters
        val movingNow = movingMillisAt(fix.elapsedRealtimeMillis)

        anchorFix = timedFix
        movingMillisAtAnchor = movingNow

        val events = mutableListOf<RunSessionEvent>(
            RunSessionEvent.PointRecorded(TrackedPoint(timedFix, segmentIndex)),
        )
        if (previous == null) return events

        val legMeters = Geo.distanceMeters(
            previous.latitude, previous.longitude, fix.latitude, fix.longitude,
        )
        distanceMeters += legMeters
        events += crossedMileMarkers(distanceBefore, legMeters, movingBefore, movingNow)
        return events
    }

    /**
     * Emits a split for every mile marker this leg ran past.
     *
     * The crossing time is interpolated across the leg rather than snapped to the
     * fix that happened to land beyond the marker — with a fix a second and miles
     * eight minutes apart, snapping would let each split borrow up to a second from
     * the next, and the error would compound over a long run.
     */
    private fun crossedMileMarkers(
        distanceBefore: Double,
        legMeters: Double,
        movingMillisBefore: Long,
        movingMillisNow: Long,
    ): List<RunSessionEvent> {
        if (legMeters <= 0.0) return emptyList()

        val events = mutableListOf<RunSessionEvent>()
        var marker = (splits.size + 1) * Units.METERS_PER_MILE
        while (distanceMeters >= marker) {
            val fraction = (marker - distanceBefore) / legMeters
            val crossingMillis = movingMillisBefore +
                ((movingMillisNow - movingMillisBefore) * fraction).toLong()
            val durationMillis = crossingMillis - movingMillisAtLastMarker
            movingMillisAtLastMarker = crossingMillis

            val split = MileSplit(
                splitNumber = splits.size + 1,
                distanceMeters = Units.METERS_PER_MILE,
                durationSec = millisToSeconds(durationMillis),
                paceSecPerMile = durationMillis / 1000.0,
                cumulativeMovingSec = millisToSeconds(crossingMillis),
            )
            splits += split
            events += RunSessionEvent.MileCompleted(split)
            marker += Units.METERS_PER_MILE
        }
        return events
    }

    /** Closes off the distance run since the last mile marker, if there is any. */
    private fun closeFinalSplit(): List<RunSessionEvent> {
        val remainder = distanceMeters - splits.size * Units.METERS_PER_MILE
        // Below a metre there is nothing worth showing in the splits table.
        if (remainder < 1.0) return emptyList()

        val totalMovingMillis = movingMillis()
        val durationMillis = totalMovingMillis - movingMillisAtLastMarker
        val durationSec = millisToSeconds(durationMillis)
        val split = MileSplit(
            splitNumber = splits.size + 1,
            distanceMeters = remainder,
            durationSec = durationSec,
            paceSecPerMile = Units.paceSecPerMile(remainder, durationSec),
            cumulativeMovingSec = millisToSeconds(totalMovingMillis),
        )
        splits += split
        return listOf(RunSessionEvent.FinalSplitCompleted(split))
    }

    private fun millisToSeconds(millis: Long): Long = (millis + 500) / 1000

    // --- internals -----------------------------------------------------------

    private fun beginTracking(elapsedRealtimeMillis: Long): RunSessionEvent {
        state = RunSessionState.TRACKING
        startedAt = wallTimeAt(elapsedRealtimeMillis)
        startedElapsedMillis = elapsedRealtimeMillis
        trackingSinceMillis = elapsedRealtimeMillis
        countdownEndsMillis = null
        countdownRemaining = 0
        // The warm-up position must not become the first leg of the run.
        anchorFix = null
        return RunSessionEvent.TrackingStarted
    }

    private fun closeTrackingWindow(elapsedRealtimeMillis: Long) {
        val since = trackingSinceMillis ?: return
        movingMillisBanked += (elapsedRealtimeMillis - since)
        trackingSinceMillis = null
    }

    /** Moving time at an instant inside the current tracking window. */
    private fun movingMillisAt(elapsedRealtimeMillis: Long): Long {
        val since = trackingSinceMillis ?: return movingMillisBanked
        return movingMillisBanked + (elapsedRealtimeMillis - since)
    }

    private fun movingMillis(): Long {
        val since = trackingSinceMillis ?: return movingMillisBanked
        val now = currentElapsedMillis ?: return movingMillisBanked
        return movingMillisBanked + (now - since)
    }

    private fun elapsedMillis(): Long {
        val from = startedElapsedMillis ?: return 0
        val to = endedElapsedMillis ?: currentElapsedMillis ?: return 0
        return to - from
    }
}
