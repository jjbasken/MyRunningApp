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
    /** The training plan in use, if any. Added in version 2. */
    val plan: BackupPlan? = null,
) {
    companion object {
        /** 2: adds [plan]. */
        const val CURRENT_VERSION = 2
    }
}

/**
 * The training plan, in the shape [com.myrunningapp.domain.plan.PlanParser]
 * imports: every workout has an explicit `date`, so this object (or the whole
 * backup file, which the parser looks inside) can be opened from the Plan tab to
 * restore the plan. A restore brings back the workouts, not their status: done
 * and skipped are recorded here for the reader, and [BackupWorkout.completedRunId]
 * points at a run in the same file.
 */
@Serializable
data class BackupPlan(
    val name: String,
    val raceDate: String?,
    val raceDistanceMeters: Double?,
    val importedAt: String,
    val workouts: List<BackupWorkout>,
)

@Serializable
data class BackupWorkout(
    /** YYYY-MM-DD, a calendar day with no time zone. */
    val date: String,
    /** easy, long, tempo, intervals, recovery, race, cross_train, rest or other. */
    val type: String,
    val title: String,
    val description: String,
    val distanceMeters: Double?,
    val durationSeconds: Long?,
    /** "9:15-9:45" per mile, for the importer and for a human reading the file. */
    val targetPace: String?,
    /** The exact pace range, which [targetPace] rounds to whole seconds. */
    val paceFastSecPerMile: Double?,
    val paceSlowSecPerMile: Double?,
    /** planned, completed or skipped. */
    val status: String,
    val completedRunId: Long?,
)

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
    val wasRecovered: Boolean = false,
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
