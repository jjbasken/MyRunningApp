package com.myrunningapp.data.location

import com.myrunningapp.domain.announce.Announcement
import com.myrunningapp.domain.announce.Announcer
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.RunSessionState
import com.myrunningapp.domain.tracking.MonotonicClock
import com.myrunningapp.domain.tracking.GpsFix
import com.myrunningapp.domain.tracking.RunSession
import com.myrunningapp.domain.tracking.RunSessionEvent
import com.myrunningapp.domain.tracking.RunSnapshot
import com.myrunningapp.domain.tracking.TrackedPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the one run that can be in progress, and writes it to the database.
 *
 * A process-wide singleton rather than state inside `LocationTrackingService`:
 * the service comes and goes with the foreground notification, while the live run
 * screen wants to observe [snapshot] without binding to a service first. The
 * service's job is to keep the process alive and to push fixes in here.
 *
 * It is also where the run finds its voice: the session reports what happened and
 * this class decides what is worth saying out loud, so the notification, the screen
 * and the announcements all follow from the same events.
 *
 * Every mutating call takes a [Mutex], so GPS callbacks and button presses cannot
 * interleave inside the [RunSession], which is not thread-safe.
 */
@Singleton
class RunTracker @Inject constructor(
    private val recorder: RunRecorder,
    private val announcer: Announcer,
    private val clock: Clock,
    private val monotonicClock: MonotonicClock,
) {

    private val mutex = Mutex()

    private val _snapshot = MutableStateFlow(RunSnapshot.idle(ActivityType.RUN))
    /** The live state of the run, for the track screen and the notification. */
    val snapshot: StateFlow<RunSnapshot> = _snapshot.asStateFlow()

    private val _lastFinishedRunId = MutableStateFlow<Long?>(null)
    /** The run just completed, so the UI can offer to open it. */
    val lastFinishedRunId: StateFlow<Long?> = _lastFinishedRunId.asStateFlow()

    private val _route = MutableStateFlow<List<TrackedPoint>>(emptyList())
    /** Accepted points, including those not yet flushed to Room. */
    val route: StateFlow<List<TrackedPoint>> = _route.asStateFlow()

    private var session: RunSession? = null
    private var runId: Long? = null

    /** Points accepted but not yet written, held back so Room is not hit per fix. */
    private val buffer = mutableListOf<TrackedPoint>()
    private var lastFlushElapsedMillis: Long? = null

    /**
     * Begins a run, after a countdown if one is configured. Does nothing if a run
     * is already under way.
     */
    suspend fun start(
        activityType: ActivityType,
        countdownSeconds: Int,
        weightKg: Double,
    ) = mutex.withLock {
        if (session?.snapshot?.isActive == true) return@withLock

        val fresh = RunSession(activityType = activityType, countdownSeconds = countdownSeconds)
        session = fresh
        runId = null
        buffer.clear()
        _route.value = emptyList()
        lastFlushElapsedMillis = null
        _lastFinishedRunId.value = null
        this.weightKg = weightKg

        handle(fresh, fresh.start(clock.instant(), monotonicClock.elapsedRealtimeMillis()))
    }

    /** Offers a fix from the location provider to the run. */
    suspend fun onLocation(fix: GpsFix) = mutex.withLock {
        val live = session ?: return@withLock
        handle(live, live.onFix(fix, clock.instant(), monotonicClock.elapsedRealtimeMillis()))
    }

    /** Drives the countdown and the on-screen timer between fixes. */
    suspend fun tick() = mutex.withLock {
        val live = session ?: return@withLock
        handle(live, live.tick(clock.instant(), monotonicClock.elapsedRealtimeMillis()))
    }

    suspend fun pause() = mutex.withLock {
        val live = session ?: return@withLock
        if (live.snapshot.state != RunSessionState.TRACKING) return@withLock
        live.pause(clock.instant(), monotonicClock.elapsedRealtimeMillis())
        flush(force = true)
        announcer.announce(Announcement.Paused)
        publish(live)
    }

    suspend fun resume() = mutex.withLock {
        val live = session ?: return@withLock
        if (live.snapshot.state != RunSessionState.PAUSED) return@withLock
        live.resume(clock.instant(), monotonicClock.elapsedRealtimeMillis())
        announcer.announce(Announcement.Resumed)
        publish(live)
    }

    /** "Start now" during a countdown. */
    suspend fun skipCountdown() = mutex.withLock {
        val live = session ?: return@withLock
        handle(live, live.skipCountdown(clock.instant(), monotonicClock.elapsedRealtimeMillis()))
    }

    /** Abandons a countdown; nothing has been written yet, so nothing to undo. */
    suspend fun cancel() = mutex.withLock {
        val live = session ?: return@withLock
        if (live.snapshot.state != RunSessionState.COUNTDOWN) return@withLock
        live.cancel(clock.instant(), monotonicClock.elapsedRealtimeMillis())
        // A countdown cue queued a moment ago would otherwise be spoken into a
        // run that no longer exists.
        announcer.stop()
        clear()
    }

    /** Ends the run, writing its last points, its final split and its summary. */
    suspend fun finish() = mutex.withLock {
        val live = session ?: return@withLock
        live.finish(clock.instant(), monotonicClock.elapsedRealtimeMillis())
        val endedAt = checkNotNull(live.timestamp)
        val summary = live.snapshot
        if (summary.state != RunSessionState.FINISHED) return@withLock

        flush(force = true)

        val id = runId
        if (id != null) {
            recorder.finishRun(id, summary, endedAt)
            _lastFinishedRunId.value = id
        }
        announcer.announce(
            Announcement.Finished(
                distanceMeters = summary.distanceMeters,
                movingDurationSec = summary.movingDurationSec,
                avgPaceSecPerMile = summary.avgPaceSecPerMile,
                activityType = summary.activityType,
            ),
        )
        clear()
    }

    // --- internals -----------------------------------------------------------

    private var weightKg: Double = 0.0

    private suspend fun handle(live: RunSession, events: List<RunSessionEvent>) {
        events.forEach { event ->
            when (event) {
                is RunSessionEvent.TrackingStarted -> openRunRow(live)
                is RunSessionEvent.PointRecorded -> {
                    buffer += event.point
                    _route.value = _route.value + event.point
                }
                else -> Unit
            }
        }
        flush(force = events.any { it is RunSessionEvent.MileCompleted })
        announce(events, live.activityType)
        publish(live)
    }

    /**
     * Turns session events into speech.
     *
     * Only whole miles are announced: [RunSessionEvent.FinalSplitCompleted] is a
     * partial mile that belongs in the splits table but would be a lie out loud,
     * and the finish line covers that stretch anyway.
     */
    private fun announce(events: List<RunSessionEvent>, activityType: ActivityType) {
        events.forEach { event ->
            when (event) {
                is RunSessionEvent.TrackingStarted ->
                    announcer.announce(Announcement.Started)
                is RunSessionEvent.CountdownTick ->
                    if (event.secondsRemaining in 1..COUNTDOWN_CUE_FROM) {
                        announcer.announce(Announcement.CountdownCue(event.secondsRemaining))
                    }
                is RunSessionEvent.MileCompleted -> announcer.announce(
                    Announcement.MileCompleted(
                        mileNumber = event.split.splitNumber,
                        totalMovingSec = event.split.cumulativeMovingSec,
                        lastMilePaceSec = event.split.paceSecPerMile,
                        activityType = activityType,
                    ),
                )
                else -> Unit
            }
        }
    }

    private suspend fun openRunRow(live: RunSession) {
        if (runId != null) return
        val startedAt = live.snapshot.startedAt ?: clock.instant()
        runId = recorder.startRun(live.activityType, startedAt, weightKg)
        lastFlushElapsedMillis = monotonicClock.elapsedRealtimeMillis()
    }

    private suspend fun flush(force: Boolean) {
        val id = runId ?: return
        val snapshot = session?.snapshot ?: return

        val now = monotonicClock.elapsedRealtimeMillis()
        val waited = lastFlushElapsedMillis?.let { now - it } ?: 0L
        val due = force || buffer.size >= MAX_BUFFERED_POINTS || waited >= FLUSH_INTERVAL
        if (!due) return

        recorder.checkpoint(id, buffer.toList(), snapshot, checkNotNull(session?.timestamp))
        buffer.clear()
        lastFlushElapsedMillis = now
    }

    private fun publish(live: RunSession) {
        _snapshot.value = live.snapshot
    }

    private fun clear() {
        session = null
        _route.value = emptyList()
        runId = null
        buffer.clear()
        lastFlushElapsedMillis = null
        _snapshot.value = RunSnapshot.idle(_snapshot.value.activityType)
    }

    private companion object {
        /** Roughly the design's "flush every ten seconds" at a 1 Hz fix rate. */
        const val FLUSH_INTERVAL = 10_000L
        const val MAX_BUFFERED_POINTS = 20
        /** The design's "3, 2, 1, go" — earlier seconds would just be nagging. */
        const val COUNTDOWN_CUE_FROM = 3
    }
}
