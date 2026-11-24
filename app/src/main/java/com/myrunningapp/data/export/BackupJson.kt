package com.myrunningapp.data.export

import com.myrunningapp.data.plan.ActivePlan
import com.myrunningapp.domain.model.Profile
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.RunPoint
import com.myrunningapp.domain.model.Split
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Builds and encodes the full-backup file. Pure: it takes rows and returns a
 * string, leaving the question of *where* the bytes go to the caller holding
 * the Storage Access Framework `Uri`.
 */
object BackupJson {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(backup: Backup): String = json.encodeToString(Backup.serializer(), backup)

    fun build(
        exportedAt: Instant,
        profile: Profile,
        runs: List<Run>,
        pointsByRun: Map<Long, List<RunPoint>>,
        splitsByRun: Map<Long, List<Split>>,
        plan: ActivePlan? = null,
    ) = Backup(
        exportedAt = iso(exportedAt),
        profile = BackupProfile(
            weightKg = profile.weightKg,
            heightCm = profile.heightCm,
            age = profile.age,
            sex = profile.sex.name,
        ),
        runs = runs.map { run ->
            BackupRun(
                id = run.id,
                startedAt = iso(run.startedAt),
                endedAt = iso(run.endedAt),
                activityType = run.activityType.name,
                distanceMeters = run.distanceMeters,
                movingDurationSec = run.movingDurationSec,
                elapsedDurationSec = run.elapsedDurationSec,
                avgPaceSecPerMile = run.avgPaceSecPerMile,
                calories = run.calories,
                weightKgAtRun = run.weightKgAtRun,
                wasRecovered = run.wasRecovered,
                splits = splitsByRun[run.id].orEmpty().map { split ->
                    BackupSplit(
                        splitNumber = split.splitNumber,
                        distanceMeters = split.distanceMeters,
                        durationSec = split.durationSec,
                        paceSecPerMile = split.paceSecPerMile,
                    )
                },
                points = pointsByRun[run.id].orEmpty().map { point ->
                    BackupPoint(
                        timestamp = iso(point.timestamp),
                        latitude = point.latitude,
                        longitude = point.longitude,
                        altitudeMeters = point.altitudeMeters,
                        accuracyMeters = point.accuracyMeters,
                        segmentIndex = point.segmentIndex,
                    )
                },
            )
        },
        plan = plan?.let(::plan),
    )

    private fun plan(active: ActivePlan) = BackupPlan(
        name = active.plan.name,
        raceDate = active.plan.raceDate?.toString(),
        raceDistanceMeters = active.plan.raceDistanceMeters,
        importedAt = iso(active.plan.importedAt),
        workouts = active.workouts.map { workout ->
            BackupWorkout(
                date = workout.date.toString(),
                type = workout.type.name.lowercase(Locale.US),
                title = workout.title,
                description = workout.description,
                distanceMeters = workout.distanceMeters,
                durationSeconds = workout.durationSec,
                targetPace = paceRange(workout.paceFastSecPerMile, workout.paceSlowSecPerMile),
                paceFastSecPerMile = workout.paceFastSecPerMile,
                paceSlowSecPerMile = workout.paceSlowSecPerMile,
                status = workout.status.name.lowercase(Locale.US),
                completedRunId = workout.completedRunId,
            )
        },
    )

    /** "9:15-9:45", or "9:30" when both ends round to the same second. */
    private fun paceRange(fast: Double?, slow: Double?): String? {
        if (fast == null || slow == null) return null
        val a = minutesSeconds(fast)
        val b = minutesSeconds(slow)
        return if (a == b) a else "$a-$b"
    }

    private fun minutesSeconds(secPerMile: Double): String {
        val total = secPerMile.roundToLong()
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    private fun iso(value: Instant): String = DateTimeFormatter.ISO_INSTANT.format(value)
}
