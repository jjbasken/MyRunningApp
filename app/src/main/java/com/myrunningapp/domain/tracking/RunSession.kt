package com.myrunningapp.domain.tracking

import com.myrunningapp.domain.Units
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.RunSessionState
import java.time.Duration
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
 * time it is, so an entire run — including its mile splits — replays in a unit
 * test in microseconds. `LocationTrackingService` owns an instance, feeds it
 * fixes and ticks, and turns the returned [RunSessionEvent]s into database writes
 * and announcements.
 *
 * Not thread-safe; the service confines it to a single coroutine.
 */
class RunSession(
    val activityType: ActivityType,
    private val countdownSeconds: Int = 0,
    private val filter: GpsFilter = GpsFilter(),
) {

    private var state: RunSessionState = RunSessionState.IDLE
    private var currentTime: Instant? = null

    private var countdownEndsAt: Instant? = null
    private var countdownRemaining: Int = 0

    private var startedAt: Instant? = null
    private var endedAt: Instant? = null

    /** Moving time from tracking windows that have already closed. */
    private var movingMillisBanked: Long = 0
    /** When the current tracking window opened, or null if not tracking. */
    private var trackingSince: Instant? = null

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
    fun start(now: Instant): List<RunSessionEvent> {
        if (state != RunSessionState.IDLE) return emptyList()
        currentTime = now
        return if (countdownSeconds > 0) {
            state = RunSessionState.COUNTDOWN
            countdownEndsAt = now.plusSeconds(countdownSeconds.toLong())
            countdownRemaining = countdownSeconds
            listOf(RunSessionEvent.CountdownTick(countdownSeconds))
        } else {
            listOf(beginTracking(now))
        }
    }

    /** "Start now" during a countdown. */
    fun skipCountdown(now: Instant): List<RunSessionEvent> {
        if (state != RunSessionState.COUNTDOWN) return emptyList()
        currentTime = now
        return listOf(beginTracking(now))
    }

    /** Abandons a countdown before it reaches zero; nothing was recorded. */
    fun cancel(now: Instant) {
        if (state != RunSessionState.COUNTDOWN) return
        currentTime = now
        state = RunSessionState.IDLE
        countdownEndsAt = null
        countdownRemaining = 0
        anchorFix = null
        lastFix = null
        splits.clear()
        movingMillisAtLastMarker = 0
    }

    fun pause(now: Instant) {
        if (state != RunSessionState.TRACKING) return
        currentTime = now
        closeTrackingWindow(now)
        state = RunSessionState.PAUSED
        anchorFix = null
    }

    fun resume(now: Instant) {
        if (state != RunSessionState.PAUSED) return
        currentTime = now
        state = RunSessionState.TRACKING
        trackingSince = now
        segmentIndex++
    }

    /** Ends the run for good. */
    fun finish(now: Instant): List<RunSessionEvent> {
        if (state != RunSessionState.TRACKING && state != RunSessionState.PAUSED) {
            return emptyList()
        }
        currentTime = now
        closeTrackingWindow(now)
        endedAt = now
        state = RunSessionState.FINISHED
        anchorFix = null
        return closeFinalSplit()
    }

    /**
     * Advances the clock without a new fix — drives the countdown and keeps the
     * on-screen timer moving between GPS updates.
     */
    fun tick(now: Instant): List<RunSessionEvent> {
        if (!snapshot.isActive) return emptyList()
        currentTime = now

        if (state != RunSessionState.COUNTDOWN) return emptyList()

        val endsAt = countdownEndsAt ?: return emptyList()
        if (!now.isBefore(endsAt)) {
            countdownRemaining = 0
            return listOf(RunSessionEvent.CountdownTick(0), beginTracking(endsAt))
        }

        val remaining = ceil(Duration.between(now, endsAt).toMillis() / 1000.0).toInt()
        if (remaining == countdownRemaining) return emptyList()
        countdownRemaining = remaining
        return listOf(RunSessionEvent.CountdownTick(remaining))
    }

    /** Offers a new location reading to the run. */
    fun onFix(fix: GpsFix, now: Instant): List<RunSessionEvent> {
        if (state != RunSessionState.COUNTDOWN && state != RunSessionState.TRACKING) {
            return emptyList()
        }
        currentTime = now

        val verdict = filter.evaluate(fix, previous = anchorFix, now = now)
        if (verdict != FixVerdict.ACCEPTED) {
            return listOf(RunSessionEvent.FixRejected(verdict))
        }
        lastFix = fix

        // Warm-up fixes only prove the GPS has a lock; they are not part of the run.
        if (state == RunSessionState.COUNTDOWN) return emptyList()

        val previous = anchorFix
        val movingBefore = movingMillisAtAnchor
        val distanceBefore = distanceMeters
        val movingNow = movingMillisAt(fix.timestamp)

        anchorFix = fix
        movingMillisAtAnchor = movingNow

        val events = mutableListOf<RunSessionEvent>(
            RunSessionEvent.PointRecorded(TrackedPoint(fix, segmentIndex)),
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

    private fun beginTracking(now: Instant): RunSessionEvent {
        state = RunSessionState.TRACKING
        startedAt = now
        trackingSince = now
        countdownEndsAt = null
        countdownRemaining = 0
        // The warm-up position must not become the first leg of the run.
        anchorFix = null
        return RunSessionEvent.TrackingStarted
    }

    private fun closeTrackingWindow(now: Instant) {
        val since = trackingSince ?: return
        movingMillisBanked += Duration.between(since, now).toMillis()
        trackingSince = null
    }

    /** Moving time at an instant inside the current tracking window. */
    private fun movingMillisAt(instant: Instant): Long {
        val since = trackingSince ?: return movingMillisBanked
        return movingMillisBanked + Duration.between(since, instant).toMillis()
    }

    private fun movingMillis(): Long {
        val since = trackingSince ?: return movingMillisBanked
        val now = currentTime ?: return movingMillisBanked
        return movingMillisBanked + Duration.between(since, now).toMillis()
    }

    private fun elapsedMillis(): Long {
        val from = startedAt ?: return 0
        val to = endedAt ?: currentTime ?: return 0
        return Duration.between(from, to).toMillis()
    }
}
