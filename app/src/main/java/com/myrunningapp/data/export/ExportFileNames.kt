package com.myrunningapp.data.export

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.Run
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Names for exported files.
 *
 * Local time, not UTC: the name is how the user finds the file in their
 * downloads folder, so it should match the clock they ran by. ASCII and
 * hyphens only — these land on whatever filesystem the picker chooses.
 */
object ExportFileNames {

    private val RUN_STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.US)
    private val BACKUP_STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    /** e.g. `run-2026-09-08-0730.gpx`, `walk-2026-09-08-1815.gpx` */
    fun forRun(run: Run, zone: ZoneId): String {
        val prefix = when (run.activityType) {
            ActivityType.RUN -> "run"
            ActivityType.WALK -> "walk"
        }
        return "$prefix-${RUN_STAMP.format(run.startedAt.atZone(zone))}.gpx"
    }

    /** e.g. `myrunningapp-backup-2026-09-08.json` */
    fun forBackup(exportedAt: Instant, zone: ZoneId): String =
        "myrunningapp-backup-${BACKUP_STAMP.format(exportedAt.atZone(zone))}.json"
}
