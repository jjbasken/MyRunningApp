package com.myrunningapp.data.health

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthSyncResultsTest {

    @Test
    fun `a completed drain succeeds`() {
        assertEquals(
            HealthSyncWorkerResult.SUCCESS,
            HealthSyncResults.forOutcome(HealthSyncOutcome.COMPLETED),
        )
    }

    @Test
    fun `only a transient problem is retried`() {
        assertEquals(
            HealthSyncWorkerResult.RETRY,
            HealthSyncResults.forOutcome(HealthSyncOutcome.RETRY_LATER),
        )
    }

    @Test
    fun `the toggle being off is a success, not a retry`() {
        assertEquals(
            HealthSyncWorkerResult.SUCCESS,
            HealthSyncResults.forOutcome(HealthSyncOutcome.DISABLED),
        )
    }

    @Test
    fun `missing permission is a success so WorkManager stops backing off`() {
        // Nothing is lost: the queue is intact, and granting permission enqueues again.
        assertEquals(
            HealthSyncWorkerResult.SUCCESS,
            HealthSyncResults.forOutcome(HealthSyncOutcome.PERMISSION_MISSING),
        )
    }

    @Test
    fun `an absent Health Connect is a success`() {
        assertEquals(
            HealthSyncWorkerResult.SUCCESS,
            HealthSyncResults.forOutcome(HealthSyncOutcome.UNAVAILABLE),
        )
    }
}
