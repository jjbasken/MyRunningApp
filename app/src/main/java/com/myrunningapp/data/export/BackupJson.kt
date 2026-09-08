package com.myrunningapp.data.export

import com.myrunningapp.domain.model.Profile
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.RunPoint
import com.myrunningapp.domain.model.Split
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter

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
    )

    private fun iso(value: Instant): String = DateTimeFormatter.ISO_INSTANT.format(value)
}
