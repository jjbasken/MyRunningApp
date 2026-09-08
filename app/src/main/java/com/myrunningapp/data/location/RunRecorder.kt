package com.myrunningapp.data.location

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.tracking.MileSplit
import com.myrunningapp.domain.tracking.RunSnapshot
import com.myrunningapp.domain.tracking.TrackedPoint
import java.time.Instant

/**
 * Everything [RunTracker] needs to persist a run, and nothing else.
 *
 * Kept as an interface so the tracker's timing rules — when a run row appears,
 * how often points are flushed — can be tested against a fake instead of Room.
 * [com.myrunningapp.data.repository.RunRepository] is the real implementation.
 */
interface RunRecorder {

    /** Creates the run row the moment tracking really begins; returns its id. */
    suspend fun startRun(
        activityType: ActivityType,
        startedAt: Instant,
        weightKg: Double,
    ): Long

    /** Atomically saves a recoverable summary, new points and the current splits. */
    suspend fun checkpoint(runId: Long, points: List<TrackedPoint>, snapshot: RunSnapshot, at: Instant)

    /** Appends a batch of accepted GPS points to a run in progress. */
    suspend fun recordPoints(runId: Long, points: List<TrackedPoint>)

    /** Stores a split at the moment its mile completes. */
    suspend fun recordSplit(runId: Long, split: MileSplit)

    /** Fills in the run's summary once it is over. */
    suspend fun finishRun(runId: Long, snapshot: RunSnapshot, endedAt: Instant)

    /** Removes a run and its track — used when a run is abandoned. */
    suspend fun discardRun(runId: Long)
}
