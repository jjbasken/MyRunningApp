package com.myrunningapp.data.health

import com.myrunningapp.domain.health.HealthAvailability
import com.myrunningapp.domain.model.HealthWorkout

class FakeHealthConnectGateway(
    var availability: HealthAvailability = HealthAvailability.AVAILABLE,
    var writePermissions: Boolean = true,
    var routePermission: Boolean = true,
) : HealthConnectGateway {

    /** Every workout handed over, newest last. A rewrite appears twice. */
    val written = mutableListOf<HealthWorkout>()
    val deleted = mutableListOf<String>()

    /** Queued results, consumed one per write. Empty means Success. */
    val writeResults = ArrayDeque<HealthWriteResult>()
    var deleteResult: HealthWriteResult = HealthWriteResult.Success

    override fun availability(): HealthAvailability = availability

    override suspend fun hasWritePermissions(): Boolean = writePermissions

    override suspend fun hasRoutePermission(): Boolean = routePermission

    /** Runs while a write is "in flight", to stand in for something racing it. */
    var onWrite: (suspend () -> Unit)? = null

    override suspend fun write(workout: HealthWorkout): HealthWriteResult {
        onWrite?.invoke()
        val result = writeResults.removeFirstOrNull() ?: HealthWriteResult.Success
        if (result is HealthWriteResult.Success) written += workout
        return result
    }

    override suspend fun delete(clientRecordId: String): HealthWriteResult {
        if (deleteResult is HealthWriteResult.Success) deleted += clientRecordId
        return deleteResult
    }
}
