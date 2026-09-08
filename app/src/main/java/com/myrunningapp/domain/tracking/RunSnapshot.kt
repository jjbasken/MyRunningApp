package com.myrunningapp.domain.tracking

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.RunSessionState
import java.time.Instant

/**
 * An immutable read of a run in progress — what the live screen and the
 * notification draw. Produced by [RunSession.snapshot].
 */
data class RunSnapshot(
    val state: RunSessionState,
    val activityType: ActivityType,
    /** When distance started accumulating; null until the countdown finishes. */
    val startedAt: Instant?,
    val distanceMeters: Double,
    val movingDurationSec: Long,
    val elapsedDurationSec: Long,
    /** Moving time per mile so far; NaN until the first metre. */
    val avgPaceSecPerMile: Double,
    /** Bumped on every resume, so the map draws one polyline per segment. */
    val segmentIndex: Int,
    /** Seconds left before tracking begins; 0 outside of a countdown. */
    val countdownSecondsRemaining: Int,
    /** The last fix the filter accepted, wherever the run currently is. */
    val lastFix: GpsFix?,
    val completedSplits: List<MileSplit>,
) {
    val isActive: Boolean
        get() = state == RunSessionState.COUNTDOWN ||
            state == RunSessionState.TRACKING ||
            state == RunSessionState.PAUSED

    companion object {
        fun idle(activityType: ActivityType) = RunSnapshot(
            state = RunSessionState.IDLE,
            activityType = activityType,
            startedAt = null,
            distanceMeters = 0.0,
            movingDurationSec = 0L,
            elapsedDurationSec = 0L,
            avgPaceSecPerMile = Double.NaN,
            segmentIndex = 0,
            countdownSecondsRemaining = 0,
            lastFix = null,
            completedSplits = emptyList(),
        )
    }
}
