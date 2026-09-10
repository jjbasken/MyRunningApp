package com.myrunningapp.data.health

/** What the worker should tell WorkManager. Mirrors `ListenableWorker.Result`. */
enum class HealthSyncWorkerResult { SUCCESS, RETRY }

/**
 * The worker's whole decision, pulled out so it can be tested as plain Kotlin —
 * a `CoroutineWorker` cannot be built without an Android runtime.
 *
 * Only a genuinely transient problem is a retry. Everything else — the toggle
 * off, no Health Connect, permission revoked — is a success: the queue is intact
 * and something else (granting permission, switching the toggle on) will enqueue
 * again. Retrying those would only make WorkManager back off further and further
 * for a condition no amount of waiting fixes.
 */
object HealthSyncResults {

    fun forOutcome(outcome: HealthSyncOutcome): HealthSyncWorkerResult = when (outcome) {
        HealthSyncOutcome.RETRY_LATER -> HealthSyncWorkerResult.RETRY
        HealthSyncOutcome.COMPLETED,
        HealthSyncOutcome.DISABLED,
        HealthSyncOutcome.UNAVAILABLE,
        HealthSyncOutcome.PERMISSION_MISSING,
        -> HealthSyncWorkerResult.SUCCESS
    }
}
