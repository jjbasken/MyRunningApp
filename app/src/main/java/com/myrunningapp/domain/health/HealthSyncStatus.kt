package com.myrunningapp.domain.health

import com.myrunningapp.domain.model.HealthSyncCounts

/** What the Health Connect section of the settings screen should show. */
sealed interface HealthSyncUiState {
    /** No Health Connect on this phone: show nothing rather than a dead toggle. */
    data object Hidden : HealthSyncUiState

    data object UpdateRequired : HealthSyncUiState

    data object Off : HealthSyncUiState

    /** Switched on but not granted — or granted and then revoked. */
    data object NeedsPermission : HealthSyncUiState

    data class Working(val pending: Int) : HealthSyncUiState

    data class UpToDate(val synced: Int) : HealthSyncUiState

    data class Failed(val failed: Int, val synced: Int) : HealthSyncUiState
}

/**
 * Decides the settings section's state from the four things that determine it.
 *
 * Pure, so every combination is tested without a device — the same shape as
 * [com.myrunningapp.domain.permission.PermissionGate].
 */
object HealthSyncStatus {

    fun of(
        availability: HealthAvailability,
        enabled: Boolean,
        writePermissionsGranted: Boolean,
        counts: HealthSyncCounts,
    ): HealthSyncUiState = when {
        availability == HealthAvailability.NOT_INSTALLED -> HealthSyncUiState.Hidden
        availability == HealthAvailability.UPDATE_REQUIRED -> HealthSyncUiState.UpdateRequired
        !enabled -> HealthSyncUiState.Off
        !writePermissionsGranted -> HealthSyncUiState.NeedsPermission
        counts.pending > 0 -> HealthSyncUiState.Working(counts.pending)
        counts.failed > 0 -> HealthSyncUiState.Failed(counts.failed, counts.synced)
        else -> HealthSyncUiState.UpToDate(counts.synced)
    }
}
