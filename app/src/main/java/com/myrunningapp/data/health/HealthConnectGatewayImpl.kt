package com.myrunningapp.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import com.myrunningapp.domain.health.HealthAvailability
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthWorkout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's one point of contact with `androidx.health`.
 *
 * Everything that could be decided without Health Connect already was, by
 * [com.myrunningapp.domain.health.WorkoutRecordBuilder] and [HealthSyncEngine].
 * What is left here is translation and error classification.
 */
@Singleton
class HealthConnectGatewayImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : HealthConnectGateway {

    /**
     * Resolved per call rather than cached with `by lazy`: availability can go
     * from unavailable to available mid-process (Health Connect gets installed
     * or updated), and `by lazy` would freeze a null result — a failed first
     * touch — for the rest of the process, with no way for the user to recover
     * short of restarting the app. Only a *successful* creation is cached.
     */
    @Volatile
    private var cachedClient: HealthConnectClient? = null

    private fun resolveClient(): HealthConnectClient? {
        cachedClient?.let { return it }
        if (availability() != HealthAvailability.AVAILABLE) return null
        return HealthConnectClient.getOrCreate(context).also { cachedClient = it }
    }

    override fun availability(): HealthAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthAvailability.UPDATE_REQUIRED
            else -> HealthAvailability.NOT_INSTALLED
        }

    override suspend fun hasWritePermissions(): Boolean =
        granted().containsAll(HealthPermissions.WRITE)

    override suspend fun hasRoutePermission(): Boolean =
        granted().contains(HealthPermissions.ROUTE)

    private suspend fun granted(): Set<String> =
        resolveClient()?.permissionController?.getGrantedPermissions() ?: emptySet()

    override suspend fun write(workout: HealthWorkout): HealthWriteResult {
        // No client means Health Connect is not currently available — an
        // availability problem, not a permission one, so this must not surface
        // as "Permission needed" in the UI. Retryable: the next drain re-resolves.
        val client = resolveClient() ?: return HealthWriteResult.Retryable
        return runCatchingHealth {
            client.insertRecords(workout.toRecords())
        }
    }

    override suspend fun delete(clientRecordId: String): HealthWriteResult {
        val client = resolveClient() ?: return HealthWriteResult.Retryable
        // Each record type is guarded on its own so one type throwing does not
        // abandon the rest — otherwise a failure on the second of three types
        // would leave the third never attempted. The most conservative outcome
        // wins: a permission problem takes priority (it stops the whole drain),
        // then "try again" (safe to re-attempt every type; delete is idempotent),
        // then "give up on this one" only once nothing is left to retry.
        var outcome: HealthWriteResult = HealthWriteResult.Success
        for (type in DELETABLE) {
            val result = runCatchingHealth {
                client.deleteRecords(
                    recordType = type,
                    recordIdsList = emptyList(),
                    clientRecordIdsList = listOf(clientRecordId),
                )
            }
            outcome = worseOf(outcome, result)
        }
        return outcome
    }

    /** Ranks outcomes from most to least conservative; see [delete]. */
    private fun worseOf(a: HealthWriteResult, b: HealthWriteResult): HealthWriteResult {
        fun rank(r: HealthWriteResult) = when (r) {
            is HealthWriteResult.PermissionMissing -> 3
            is HealthWriteResult.Retryable -> 2
            is HealthWriteResult.Rejected -> 1
            is HealthWriteResult.Success -> 0
        }
        return if (rank(b) > rank(a)) b else a
    }

    /**
     * Classifies what went wrong. The distinction that matters is between "try
     * again in a minute" and "this will never work": the first leaves the run
     * queued, the second marks it failed rather than retrying forever.
     */
    private inline fun runCatchingHealth(block: () -> Unit): HealthWriteResult = try {
        block()
        HealthWriteResult.Success
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        HealthWriteResult.PermissionMissing
    } catch (e: IOException) {
        HealthWriteResult.Retryable
    } catch (e: IllegalStateException) {
        // Health Connect updating or otherwise not ready.
        HealthWriteResult.Retryable
    } catch (e: IllegalArgumentException) {
        HealthWriteResult.Rejected(e.message ?: "rejected by Health Connect")
    }

    private fun HealthWorkout.toRecords(): List<Record> {
        val zone: ZoneOffset = ZoneId.systemDefault().rules.getOffset(startedAt)
        val metadata = Metadata.activelyRecorded(
            device = Device(type = Device.TYPE_PHONE),
            clientRecordId = clientRecordId,
            // Rises with every re-queue of the run. Health Connect retains
            // whichever copy of a client record id carries the higher version,
            // so leaving this at its default would let it discard a rewrite —
            // an edit that never lands while the row is marked SYNCED.
            clientRecordVersion = clientRecordVersion,
        )
        val session = ExerciseSessionRecord(
            startTime = startedAt,
            startZoneOffset = zone,
            endTime = endedAt,
            endZoneOffset = zone,
            exerciseType = when (activityType) {
                ActivityType.RUN -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                ActivityType.WALK -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
            },
            title = title,
            segments = segments.map {
                ExerciseSegment(
                    startTime = it.startedAt,
                    endTime = it.endedAt,
                    segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_UNKNOWN,
                )
            },
            laps = laps.map {
                ExerciseLap(
                    startTime = it.startedAt,
                    endTime = it.endedAt,
                    length = Length.meters(it.distanceMeters),
                )
            },
            exerciseRoute = route.takeIf { it.isNotEmpty() }?.let { points ->
                ExerciseRoute(
                    points.map {
                        ExerciseRoute.Location(
                            time = it.time,
                            latitude = it.latitude,
                            longitude = it.longitude,
                            altitude = Length.meters(it.altitudeMeters),
                            horizontalAccuracy = Length.meters(it.horizontalAccuracyMeters.toDouble()),
                        )
                    },
                )
            },
            metadata = metadata,
        )
        return listOf(
            session,
            DistanceRecord(
                startTime = startedAt, startZoneOffset = zone,
                endTime = endedAt, endZoneOffset = zone,
                distance = Length.meters(distanceMeters),
                metadata = metadata,
            ),
            ActiveCaloriesBurnedRecord(
                startTime = startedAt, startZoneOffset = zone,
                endTime = endedAt, endZoneOffset = zone,
                energy = Energy.kilocalories(activeCalories.toDouble()),
                metadata = metadata,
            ),
        )
    }

    private companion object {
        val DELETABLE = listOf(
            ExerciseSessionRecord::class,
            DistanceRecord::class,
            ActiveCaloriesBurnedRecord::class,
        )
    }
}
