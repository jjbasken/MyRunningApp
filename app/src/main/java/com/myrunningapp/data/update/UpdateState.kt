package com.myrunningapp.data.update

import android.content.Intent
import android.content.pm.PackageInstaller
import com.myrunningapp.domain.update.ReleaseInfo

/** Where the updater is, for the settings screen. */
sealed interface UpdateState {
    /** A debug build: different package name and signing key, so nothing to install onto. */
    data object Unsupported : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val fraction: Float) : UpdateState
    /** Handed to the system installer; waiting for its verdict. */
    data class Installing(val release: ReleaseInfo) : UpdateState

    /**
     * Android wants the user to confirm, and [confirmIntent] is its dialog. The
     * receiver tries to open it straight away, but Android blocks activity
     * starts from the background — if the user left the app mid-download the
     * dialog never appears — so the screen offers it as a button too.
     */
    data class AwaitingConfirmation(val release: ReleaseInfo, val confirmIntent: Intent) : UpdateState
    data class Failed(val message: String, val release: ReleaseInfo?) : UpdateState

    /** The release an install action applies to, if this state has one. */
    val installableRelease: ReleaseInfo?
        get() = when (this) {
            is Available -> release
            is Failed -> release
            is Installing -> release
            is AwaitingConfirmation -> release
            else -> null
        }

    companion object {
        /**
         * Maps the package installer's final status to the next state. [release]
         * is what was being installed, or null if this process has forgotten
         * (it died while the system dialog was up).
         */
        fun afterInstall(status: Int, message: String?, release: ReleaseInfo?): UpdateState = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpToDate
            // The user backed out of the system dialog: offer the same update again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> release?.let { Available(it) } ?: Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> Failed(
                "This update is signed differently from the installed app. Install it manually from GitHub.",
                release,
            )
            PackageInstaller.STATUS_FAILURE_STORAGE -> Failed("Not enough storage to install the update", release)
            else -> Failed(message ?: "The installer refused the update", release)
        }
    }
}
