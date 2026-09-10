package com.myrunningapp.domain.health

import com.myrunningapp.domain.model.HealthSyncCounts
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthSyncStatusTest {

    private fun status(
        availability: HealthAvailability = HealthAvailability.AVAILABLE,
        enabled: Boolean = true,
        granted: Boolean = true,
        counts: HealthSyncCounts = HealthSyncCounts(),
    ) = HealthSyncStatus.of(availability, enabled, granted, counts)

    @Test
    fun `the section is hidden when Health Connect is not installed`() {
        assertEquals(
            HealthSyncUiState.Hidden,
            status(availability = HealthAvailability.NOT_INSTALLED, enabled = false),
        )
    }

    @Test
    fun `it stays hidden even if the toggle was left on from a previous device`() {
        assertEquals(
            HealthSyncUiState.Hidden,
            status(availability = HealthAvailability.NOT_INSTALLED, enabled = true),
        )
    }

    @Test
    fun `an outdated Health Connect asks to be updated`() {
        assertEquals(
            HealthSyncUiState.UpdateRequired,
            status(availability = HealthAvailability.UPDATE_REQUIRED),
        )
    }

    @Test
    fun `the toggle being off beats everything else`() {
        assertEquals(
            HealthSyncUiState.Off,
            status(enabled = false, counts = HealthSyncCounts(pending = 3, failed = 1)),
        )
    }

    @Test
    fun `permission missing is reported before any queue counts`() {
        assertEquals(
            HealthSyncUiState.NeedsPermission,
            status(granted = false, counts = HealthSyncCounts(pending = 3)),
        )
    }

    @Test
    fun `a queue that is draining reports what is left`() {
        assertEquals(
            HealthSyncUiState.Working(pending = 3),
            status(counts = HealthSyncCounts(pending = 3, synced = 10)),
        )
    }

    @Test
    fun `failures are reported once the queue has drained`() {
        assertEquals(
            HealthSyncUiState.Failed(failed = 2, synced = 10),
            status(counts = HealthSyncCounts(synced = 10, failed = 2)),
        )
    }

    @Test
    fun `a drained queue reports how many runs are published`() {
        assertEquals(
            HealthSyncUiState.UpToDate(synced = 42),
            status(counts = HealthSyncCounts(synced = 42)),
        )
    }

    @Test
    fun `just switched on with nothing done yet is up to date with zero`() {
        assertEquals(HealthSyncUiState.UpToDate(synced = 0), status())
    }
}
