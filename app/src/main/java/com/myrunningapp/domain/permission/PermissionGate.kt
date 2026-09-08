package com.myrunningapp.domain.permission

/**
 * What the app has been granted right now. Callers on Android 9 and older pass
 * `backgroundLocationGranted = true`: there is no separate background permission
 * before API 29, so nothing is missing there and the gate should not ask for it.
 */
data class PermissionStatus(
    val fineLocationGranted: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    /** Whether the background rationale has been shown once already. */
    val backgroundAlreadyAsked: Boolean = false,
    /** Whether the user has dismissed the "screen-off tracking" warning. */
    val backgroundWarningDismissed: Boolean = false,
)

/** The one thing to do next before a run can start. */
sealed interface PermissionStep {
    /** Explain why location is needed, then request it. Blocks the run. */
    data object ForegroundRationale : PermissionStep

    /**
     * Explain background location, then request it. Does *not* block the run —
     * the user can decline and still track with the app open.
     */
    data object BackgroundRationale : PermissionStep

    /** Nothing left to ask; start the run. */
    data object Ready : PermissionStep
}

/**
 * Decides which permission conversation to have, one at a time.
 *
 * Android only shows its own dialog once per permission, so the order matters:
 * foreground location first because tracking cannot happen without it, then
 * background location as a separate, optional second ask. Notifications ride
 * along with the foreground request and never gate anything — a denied
 * notification costs the user the live stats, not the run.
 *
 * Pure, so the whole flow is testable without a device.
 */
object PermissionGate {

    fun next(status: PermissionStatus): PermissionStep = when {
        !status.fineLocationGranted -> PermissionStep.ForegroundRationale
        !status.backgroundLocationGranted && !status.backgroundAlreadyAsked ->
            PermissionStep.BackgroundRationale
        else -> PermissionStep.Ready
    }

    /**
     * Whether to show the "tracking may stop with the screen off" banner: only
     * once the run itself is possible, only while background is actually
     * missing, and only until the user waves it away.
     */
    fun showsBackgroundWarning(status: PermissionStatus): Boolean =
        status.fineLocationGranted &&
            !status.backgroundLocationGranted &&
            !status.backgroundWarningDismissed
}
