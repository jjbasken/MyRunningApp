package com.myrunningapp.data.export

import com.myrunningapp.domain.model.Profile
import com.myrunningapp.domain.model.Sex
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BackupJsonTest {

    private val profile = Profile(weightKg = 75.0, heightCm = 180.0, age = 41, sex = Sex.MALE)
    private val exportedAt = Instant.parse("2026-09-08T19:04:05Z")

    private fun backup() = BackupJson.build(
        exportedAt = exportedAt,
        profile = profile,
        runs = listOf(ExportFixtures.run),
        pointsByRun = mapOf(7L to ExportFixtures.points),
        splitsByRun = mapOf(7L to ExportFixtures.splits),
    )

    @Test
    fun `carries every run with its points and splits`() {
        val backup = backup()
        assertEquals(1, backup.runs.size)
        val run = backup.runs.single()
        assertEquals(7L, run.id)
        assertEquals(3, run.points.size)
        assertEquals(1, run.splits.size)
        assertEquals(118, run.calories)
    }

    @Test
    fun `stamps the current format version`() {
        assertEquals(Backup.CURRENT_VERSION, backup().version)
    }

    @Test
    fun `times are ISO-8601 strings a person can read`() {
        val backup = backup()
        assertEquals("2026-09-08T19:04:05Z", backup.exportedAt)
        assertEquals("2026-09-08T07:30:00Z", backup.runs.single().startedAt)
        assertEquals("2026-09-08T07:30:00Z", backup.runs.single().points.first().timestamp)
    }

    @Test
    fun `snapshot weight is exported alongside the profile weight`() {
        val backup = backup()
        assertEquals(75.0, backup.profile.weightKg, 0.0001)
        assertEquals(75.0, backup.runs.single().weightKgAtRun, 0.0001)
    }

    @Test
    fun `runs with no track still export`() {
        val backup = BackupJson.build(
            exportedAt = exportedAt,
            profile = profile,
            runs = listOf(ExportFixtures.run),
            pointsByRun = emptyMap(),
            splitsByRun = emptyMap(),
        )
        assertTrue(backup.runs.single().points.isEmpty())
        assertTrue(backup.runs.single().splits.isEmpty())
    }

    @Test
    fun `encodes to JSON that decodes back to the same backup`() {
        // A backup nobody can read back is not a backup.
        val original = backup()
        val text = BackupJson.encode(original)
        assertEquals(original, Json.decodeFromString(Backup.serializer(), text))
    }

    @Test
    fun `encoded JSON is pretty-printed`() {
        assertTrue(BackupJson.encode(backup()).contains("\n"))
    }
}
