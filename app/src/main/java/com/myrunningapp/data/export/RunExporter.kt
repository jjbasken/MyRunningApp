package com.myrunningapp.data.export

import com.myrunningapp.data.repository.ProfileRepository
import com.myrunningapp.data.repository.RunRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** A file ready to be written: what to call it and what goes in it. */
data class ExportDocument(val fileName: String, val mimeType: String, val content: String)

/**
 * Gathers what an export needs and hands back the finished text.
 *
 * Deliberately stops short of the filesystem: the Storage Access Framework
 * hands a `Uri` to the *screen*, and that is where the bytes get written. Keeping
 * the `Uri` out of here leaves this class testable and leaves the writers
 * ([GpxWriter], [BackupJson]) pure.
 */
@Singleton
class RunExporter @Inject constructor(
    private val runRepository: RunRepository,
    private val profileRepository: ProfileRepository,
) {

    /** One run as GPX, for handing to another running app. */
    suspend fun exportRun(runId: Long, zone: ZoneId = ZoneId.systemDefault()): ExportDocument? {
        val run = runRepository.getRun(runId) ?: return null
        val points = runRepository.points(runId).first()
        return ExportDocument(
            fileName = ExportFileNames.forRun(run, zone),
            mimeType = GPX_MIME,
            content = GpxWriter.write(run, points),
        )
    }

    /**
     * Everything, as one JSON file — the backup the design leans on instead of a
     * server. Loads every run's track, so it is deliberately a foreground action
     * the user asks for rather than anything automatic.
     */
    suspend fun exportEverything(
        exportedAt: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ExportDocument {
        val runs = runRepository.runs.first()
        val backup = BackupJson.build(
            exportedAt = exportedAt,
            profile = profileRepository.get(),
            runs = runs,
            pointsByRun = runs.associate { it.id to runRepository.points(it.id).first() },
            splitsByRun = runs.associate { it.id to runRepository.splits(it.id).first() },
        )
        return ExportDocument(
            fileName = ExportFileNames.forBackup(exportedAt, zone),
            mimeType = JSON_MIME,
            content = BackupJson.encode(backup),
        )
    }

    companion object {
        const val GPX_MIME = "application/gpx+xml"
        const val JSON_MIME = "application/json"
    }
}
