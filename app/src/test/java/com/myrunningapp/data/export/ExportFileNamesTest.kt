package com.myrunningapp.data.export

import com.myrunningapp.domain.model.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ExportFileNamesTest {

    private val seattle: ZoneId = ZoneId.of("America/Los_Angeles")

    @Test
    fun `a run is named for its local start time`() {
        // 07:30 UTC is 00:30 in Seattle — the file should say the latter,
        // because that is the clock the user ran by.
        assertEquals(
            "run-2026-09-08-0030.gpx",
            ExportFileNames.forRun(ExportFixtures.run, seattle),
        )
    }

    @Test
    fun `a walk is named as a walk`() {
        assertEquals(
            "walk-2026-09-08-0030.gpx",
            ExportFileNames.forRun(
                ExportFixtures.run.copy(activityType = ActivityType.WALK),
                seattle,
            ),
        )
    }

    @Test
    fun `the backup is named for the day it was taken`() {
        assertEquals(
            "myrunningapp-backup-2026-09-08.json",
            ExportFileNames.forBackup(Instant.parse("2026-09-09T04:00:00Z"), seattle),
        )
    }

    @Test
    fun `names carry nothing a filesystem would object to`() {
        val name = ExportFileNames.forRun(ExportFixtures.run, ZoneId.of("UTC"))
        assertEquals(name, name.filter { it.isLetterOrDigit() || it == '-' || it == '.' })
    }

    @Test
    fun `a ride is named as a ride`() {
        assertEquals(
            "ride-2026-09-08-0030.gpx",
            ExportFileNames.forRun(
                ExportFixtures.run.copy(activityType = ActivityType.BIKE),
                seattle,
            ),
        )
    }
}
