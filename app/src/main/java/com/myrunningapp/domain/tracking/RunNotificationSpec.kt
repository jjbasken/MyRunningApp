package com.myrunningapp.domain.tracking

import com.myrunningapp.domain.model.RunSessionState

/** A button on the run notification. Each maps to a tracking service action. */
enum class RunNotificationAction {
    SKIP_COUNTDOWN,
    CANCEL,
    PAUSE,
    RESUME,
    FINISH,
}

/**
 * What the ongoing-run notification should say and offer, derived from a
 * snapshot alone.
 *
 * Kept separate from the service so the rules — which controls belong to which
 * state, and that a finished run offers none — are unit-tested rather than
 * inspected by hand on a phone. The service turns [actions] into labelled
 * `PendingIntent`s and the numbers into text.
 */
data class RunNotificationSpec(
    val state: RunSessionState,
    val countdownSecondsRemaining: Int,
    val distanceMeters: Double,
    val movingDurationSec: Long,
    val actions: List<RunNotificationAction>,
) {
    companion object {
        fun forSnapshot(snapshot: RunSnapshot) = RunNotificationSpec(
            state = snapshot.state,
            countdownSecondsRemaining = snapshot.countdownSecondsRemaining,
            distanceMeters = snapshot.distanceMeters,
            movingDurationSec = snapshot.movingDurationSec,
            actions = actionsFor(snapshot.state),
        )

        private fun actionsFor(state: RunSessionState): List<RunNotificationAction> = when (state) {
            RunSessionState.COUNTDOWN -> listOf(
                RunNotificationAction.SKIP_COUNTDOWN,
                RunNotificationAction.CANCEL,
            )
            RunSessionState.TRACKING -> listOf(
                RunNotificationAction.PAUSE,
                RunNotificationAction.FINISH,
            )
            RunSessionState.PAUSED -> listOf(
                RunNotificationAction.RESUME,
                RunNotificationAction.FINISH,
            )
            // The notification is on its way out in both cases; offering a
            // control here would fire at a tracker that has already moved on.
            RunSessionState.IDLE, RunSessionState.FINISHED -> emptyList()
        }
    }
}
