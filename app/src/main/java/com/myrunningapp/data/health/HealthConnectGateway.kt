package com.myrunningapp.data.health

import com.myrunningapp.domain.health.HealthAvailability
import com.myrunningapp.domain.model.HealthWorkout

/** What came of trying to write or delete one workout. */
sealed interface HealthWriteResult {
    data object Success : HealthWriteResult

    /** Not granted, or granted and later revoked. Not a failure — nothing to retry against. */
    data object PermissionMissing : HealthWriteResult

    /** Health Connect was busy, updating or otherwise temporarily unable. Try again later. */
    data object Retryable : HealthWriteResult

    /** Refused for a reason retrying will not fix, e.g. a malformed record. */
    data class Rejected(val reason: String) : HealthWriteResult
}

/**
 * The app's whole surface onto Health Connect.
 *
 * An interface for the same reason [com.myrunningapp.domain.announce.Announcer]
 * is one: it lets [HealthSyncEngine]'s rules be tested against a fake, with no
 * device and no Health Connect installed. Only
 * [HealthConnectGatewayImpl] mentions `androidx.health`.
 */
interface HealthConnectGateway {

    fun availability(): HealthAvailability

    /** The session, distance and calorie write permissions — the ones sync needs. */
    suspend fun hasWritePermissions(): Boolean

    /** The separate, more sensitive route permission. Sync works without it. */
    suspend fun hasRoutePermission(): Boolean

    suspend fun write(workout: HealthWorkout): HealthWriteResult

    /** Deletes by client record id, so no Health Connect uid ever has to be stored. */
    suspend fun delete(clientRecordId: String): HealthWriteResult
}
