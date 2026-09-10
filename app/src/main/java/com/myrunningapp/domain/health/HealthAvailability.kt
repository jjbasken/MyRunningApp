package com.myrunningapp.domain.health

/**
 * Whether this phone can accept workouts at all.
 *
 * Health Connect is part of the OS from Android 14 but a separate app before it,
 * so "not installed" is an ordinary state rather than an error.
 */
enum class HealthAvailability {
    NOT_INSTALLED,
    UPDATE_REQUIRED,
    AVAILABLE,
}
