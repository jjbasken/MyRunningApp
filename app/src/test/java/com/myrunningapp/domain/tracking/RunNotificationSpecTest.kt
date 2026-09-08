package com.myrunningapp.domain.tracking

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.RunSessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class RunNotificationSpecTest {

    private fun snapshot(
        state: RunSessionState,
        distanceMeters: Double = 0.0,
        movingDurationSec: Long = 0L,
        countdownSecondsRemaining: Int = 0,
    ) = RunSnapshot.idle(ActivityType.RUN).copy(
        state = state,
        distanceMeters = distanceMeters,
        movingDurationSec = movingDurationSec,
        countdownSecondsRemaining = countdownSecondsRemaining,
    )

    @Test
    fun `countdown offers skip and cancel`() {
        val spec = RunNotificationSpec.forSnapshot(
            snapshot(RunSessionState.COUNTDOWN, countdownSecondsRemaining = 12),
        )
        assertEquals(
            listOf(RunNotificationAction.SKIP_COUNTDOWN, RunNotificationAction.CANCEL),
            spec.actions,
        )
        assertEquals(12, spec.countdownSecondsRemaining)
    }

    @Test
    fun `tracking offers pause and finish`() {
        val spec = RunNotificationSpec.forSnapshot(snapshot(RunSessionState.TRACKING))
        assertEquals(
            listOf(RunNotificationAction.PAUSE, RunNotificationAction.FINISH),
            spec.actions,
        )
    }

    @Test
    fun `paused offers resume rather than pause`() {
        val spec = RunNotificationSpec.forSnapshot(snapshot(RunSessionState.PAUSED))
        assertEquals(
            listOf(RunNotificationAction.RESUME, RunNotificationAction.FINISH),
            spec.actions,
        )
    }

    @Test
    fun `a run that is over offers nothing`() {
        assertEquals(
            emptyList<RunNotificationAction>(),
            RunNotificationSpec.forSnapshot(snapshot(RunSessionState.FINISHED)).actions,
        )
        assertEquals(
            emptyList<RunNotificationAction>(),
            RunNotificationSpec.forSnapshot(snapshot(RunSessionState.IDLE)).actions,
        )
    }

    @Test
    fun `carries the live numbers through`() {
        val spec = RunNotificationSpec.forSnapshot(
            snapshot(RunSessionState.TRACKING, distanceMeters = 3218.7, movingDurationSec = 1080),
        )
        assertEquals(3218.7, spec.distanceMeters, 0.001)
        assertEquals(1080L, spec.movingDurationSec)
    }
}
