package com.myrunningapp.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.myrunningapp.domain.tracking.GpsFix
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A stream of GPS fixes. An interface so the tracking service can be pointed at a
 * recorded trace instead of a real receiver.
 */
interface LocationClient {
    /** Emits fixes until the collector stops. Requires location permission. */
    fun fixes(): Flow<GpsFix>
}

/**
 * [LocationClient] backed by Google Play Services' fused provider — the same
 * source the platform's own navigation uses, and, unlike Google Maps, needing no
 * API key.
 */
@Singleton
class FusedLocationClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: FusedLocationProviderClient,
) : LocationClient {

    /**
     * The caller checks the permission before starting the service; annotating it
     * away here keeps the check in one place rather than in every collector.
     */
    @SuppressLint("MissingPermission")
    override fun fixes(): Flow<GpsFix> = callbackFlow {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MILLIS,
        )
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MILLIS)
            // A fix that is a little late is still worth having on a run.
            .setMaxUpdateDelayMillis(UPDATE_INTERVAL_MILLIS * 2)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { trySend(it.toGpsFix()) }
            }
        }

        client.requestLocationUpdates(request, callback, context.mainLooper)
        awaitClose { client.removeLocationUpdates(callback) }
    }

    private companion object {
        const val UPDATE_INTERVAL_MILLIS = 1_000L
    }
}

private fun Location.toGpsFix(): GpsFix = GpsFix(
    timestamp = Instant.ofEpochMilli(time),
    elapsedRealtimeMillis = elapsedRealtimeNanos / 1_000_000L,
    latitude = latitude,
    longitude = longitude,
    altitudeMeters = if (hasAltitude()) altitude else 0.0,
    // A fix with no accuracy estimate is one the filter should not trust.
    accuracyMeters = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
)
