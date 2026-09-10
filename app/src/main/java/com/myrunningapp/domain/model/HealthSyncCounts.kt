package com.myrunningapp.domain.model

/**
 * How many runs sit in each sync state. Drives the settings screen's status
 * line, and lives in `domain` because the pure status decision reads it —
 * `domain` must never have to import from `data`.
 */
data class HealthSyncCounts(
    val pending: Int = 0,
    val synced: Int = 0,
    val failed: Int = 0,
)
