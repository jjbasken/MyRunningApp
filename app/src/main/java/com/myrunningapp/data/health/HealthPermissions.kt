package com.myrunningapp.data.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord

/**
 * What the app asks Health Connect for.
 *
 * The route is separate and asked for alongside the rest: it is the more
 * sensitive one, and sync works without it, so a refusal costs the map and
 * nothing else.
 */
object HealthPermissions {

    val WRITE: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
    )

    const val ROUTE: String = HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE

    val ALL: Set<String> = WRITE + ROUTE
}
