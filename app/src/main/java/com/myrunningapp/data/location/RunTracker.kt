package com.myrunningapp.data.location

import com.myrunningapp.domain.model.ActivityType
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
import java.time.Duration
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
 * Every mutating call takes a [Mutex], so GPS callbacks and button presses cannot
 * interleave inside the [RunSession], which is not thread-safe.
 */
@Singleton
class RunTracker @Inject constructor(
    private val recorder: RunRecorder,
    private val clock: Clock,
) {

    private val mutex = Mutex()

    private val _snapshot = MutableStateFlow(RunSnapshot.idle(ActivityType.RUN))
    /** The live state of the run, for the track screen and the notification. */
    val snapshot: StateFlow<RunSnapshot> = _snapshot.asStateFlow()

    private val _lastFinishedRunId = MutableStateFlow<Long?>(null)
    /** The run just completed, so the UI can offer to open it. */
    val lastFinishedRunId: StateFlow<Long?> = _lastFinishedRunId.asStateFlow()

    private var session: RunSession? = null
    private var runId: Long? = null

    /** Points accepted but not yet written, held back so Room is not hit per fix. */
    private val buffer = mutableListOf<TrackedPoint>()
    private var lastFlushAt: Instant? = null

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
        lastFlushAt = null
        _lastFinishedRunId.value = null
        this.weightKg = weightKg

        handle(fresh, fresh.start(clock.instant()))
    }

    /** Offers a fix from the location provider to the run. */
    suspend fun onLocation(fix: GpsFix) = mutex.withLock {
        val live = session ?: return@withLock
        handle(live, live.onFix(fix, clock.instant()))
    }

    /** Drives the countdown and the on-screen timer between fixes. */
    suspend fun tick() = mutex.withLock {
        val live = session ?: return@withLock
        handle(live, live.tick(clock.instant()))
    }

    suspend fun pause() = mutex.withLock {
        val live = session ?: return@withLock
        live.pause(clock.instant())
        flush(force = true)
        publish(live)
    }

    suspend fun resume() = mutex.withLock {
        val live = session ?: return@withLock
        live.resume(clock.instant())
        publish(live)
    }

    /** "Start now" during a countdown. */
    suspend fun skipCountdown() = mutex.withLock {
        val live = session ?: return@withLock
        handle(live, live.skipCountdown(clock.instant()))
    }

    /** Abandons a countdown; nothing has been written yet, so nothing to undo. */
    suspend fun cancel() = mutex.withLock {
        val live = session ?: return@withLock
        live.cancel(clock.instant())
        clear()
    }

    /** Ends the run, writing its last points, its final split and its summary. */
    suspend fun finish() = mutex.withLock {
        val live = session ?: return@withLock
        val endedAt = clock.instant()
        val events = live.finish(endedAt)
        if (live.snapshot.state != com.myrunningapp.domain.model.RunSessionState.FINISHED) {
            return@withLock
        }

        flush(force = true)
        handleSplits(events)

        val id = runId
        if (id != null) {
            recorder.finishRun(id, live.snapshot, endedAt)
            _lastFinishedRunId.value = id
        }
        clear()
    }

    // --- internals -----------------------------------------------------------

    private var weightKg: Double = 0.0

    private suspend fun handle(live: RunSession, events: List<RunSessionEvent>) {
        events.forEach { event ->
            when (event) {
                is RunSessionEvent.TrackingStarted -> openRunRow(live)
                is RunSessionEvent.PointRecorded -> buffer += event.point
                else -> Unit
            }
        }
        flush(force = false)
        handleSplits(events)
        publish(live)
    }

    private suspend fun openRunRow(live: RunSession) {
        if (runId != null) return
        val startedAt = live.snapshot.startedAt ?: clock.instant()
        runId = recorder.startRun(live.activityType, startedAt, weightKg)
        lastFlushAt = startedAt
    }

    /**
     * Splits are written straight through rather than buffered: there is at most
     * one every several minutes, and losing one to a crash would leave a gap in
     * the numbers the run already announced.
     */
    private suspend fun handleSplits(events: List<RunSessionEvent>) {
        val id = runId ?: return
        events.forEach { event ->
            when (event) {
                is RunSessionEvent.MileCompleted -> recorder.recordSplit(id, event.split)
                is RunSessionEvent.FinalSplitCompleted -> recorder.recordSplit(id, event.split)
                else -> Unit
            }
        }
    }

    private suspend fun flush(force: Boolean) {
        val id = runId ?: return
        if (buffer.isEmpty()) return

        val now = clock.instant()
        val waited = lastFlushAt?.let { Duration.between(it, now) } ?: Duration.ZERO
        val due = force || buffer.size >= MAX_BUFFERED_POINTS || waited >= FLUSH_INTERVAL
        if (!due) return

        recorder.recordPoints(id, buffer.toList())
        buffer.clear()
        lastFlushAt = now
    }

    private fun publish(live: RunSession) {
        _snapshot.value = live.snapshot
    }

    private fun clear() {
        session = null
        runId = null
        buffer.clear()
        lastFlushAt = null
        _snapshot.value = RunSnapshot.idle(_snapshot.value.activityType)
    }

    private companion object {
        /** Roughly the design's "flush every ten seconds" at a 1 Hz fix rate. */
        val FLUSH_INTERVAL: Duration = Duration.ofSeconds(10)
        const val MAX_BUFFERED_POINTS = 20
    }
}
