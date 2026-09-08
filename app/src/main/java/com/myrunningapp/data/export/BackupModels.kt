package com.myrunningapp.data.export

import kotlinx.serialization.Serializable

/**
 * The shape of the "export all data" file.
 *
 * This is a wire format, deliberately declared apart from the domain models:
 * a backup written today has to stay readable after the app's internals move
 * on, so the two are free to drift and [version] records which shape a file was
 * written in. Times are ISO-8601 UTC strings rather than epoch numbers so the
 * file stays readable by a human with a text editor.
 */
@Serializable
data class Backup(
    val version: Int = CURRENT_VERSION,
    val exportedAt: String,
    val profile: BackupProfile,
    val runs: List<BackupRun>,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class BackupProfile(
    val weightKg: Double,
    val heightCm: Double,
    val age: Int,
    val sex: String,
)

@Serializable
data class BackupRun(
    val id: Long,
    val startedAt: String,
    val endedAt: String,
    val activityType: String,
    val distanceMeters: Double,
    val movingDurationSec: Long,
    val elapsedDurationSec: Long,
    val avgPaceSecPerMile: Double,
    val calories: Int,
    val weightKgAtRun: Double,
    val splits: List<BackupSplit>,
    val points: List<BackupPoint>,
)

@Serializable
data class BackupSplit(
    val splitNumber: Int,
    val distanceMeters: Double,
    val durationSec: Long,
    val paceSecPerMile: Double,
)

@Serializable
data class BackupPoint(
    val timestamp: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val accuracyMeters: Float,
    val segmentIndex: Int,
)
