package com.myrunningapp.data.update

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
    /** Handed to the system installer; it may be showing its own confirmation. */
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Failed(val message: String, val release: ReleaseInfo?) : UpdateState
}
