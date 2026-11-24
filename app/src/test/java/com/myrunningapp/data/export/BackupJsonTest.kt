package com.myrunningapp.data.export

import com.myrunningapp.data.plan.ActivePlan
import com.myrunningapp.domain.Units
import com.myrunningapp.domain.model.Profile
import com.myrunningapp.domain.model.Sex
import com.myrunningapp.domain.plan.PlanParser
import com.myrunningapp.domain.plan.PlannedWorkout
import com.myrunningapp.domain.plan.TrainingPlan
import com.myrunningapp.domain.plan.WorkoutStatus
import com.myrunningapp.domain.plan.WorkoutType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

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

    // --- the training plan --------------------------------------------------------

    private val trainingPlan = TrainingPlan(
        id = 1, name = "Half plan", raceDate = LocalDate.of(2027, 11, 14),
        raceDistanceMeters = Units.milesToMeters(13.1), importedAt = Instant.parse("2026-09-01T12:00:00Z"),
    )

    private fun workout(
        id: Long,
        date: LocalDate,
        type: WorkoutType,
        status: WorkoutStatus = WorkoutStatus.PLANNED,
        runId: Long? = null,
        distanceMiles: Double? = 3.0,
        durationSec: Long? = null,
    ) = PlannedWorkout(
        id = id, planId = 1, date = date, type = type, title = "Workout $id", description = "Note $id",
        distanceMeters = distanceMiles?.let(Units::milesToMeters), durationSec = durationSec,
        paceFastSecPerMile = 555.4, paceSlowSecPerMile = 585.0, status = status, completedRunId = runId,
    )

    private val workouts = listOf(
        workout(1, LocalDate.of(2026, 9, 8), WorkoutType.EASY, WorkoutStatus.COMPLETED, runId = 7),
        workout(2, LocalDate.of(2026, 9, 10), WorkoutType.CROSS_TRAIN, WorkoutStatus.SKIPPED, distanceMiles = null, durationSec = 1800),
        workout(3, LocalDate.of(2026, 9, 13), WorkoutType.LONG),
    )

    private fun backupWithPlan() = BackupJson.build(
        exportedAt = exportedAt,
        profile = profile,
        runs = listOf(ExportFixtures.run),
        pointsByRun = mapOf(7L to ExportFixtures.points),
        splitsByRun = mapOf(7L to ExportFixtures.splits),
        plan = ActivePlan(trainingPlan, workouts),
    )

    @Test
    fun `a backup with no plan says so`() {
        assertNull(backup().plan)
    }

    @Test
    fun `the plan is backed up with every workout, its status and its run`() {
        val plan = backupWithPlan().plan!!
        assertEquals("Half plan", plan.name)
        assertEquals("2027-11-14", plan.raceDate)
        assertEquals("2026-09-01T12:00:00Z", plan.importedAt)
        assertEquals(listOf("2026-09-08", "2026-09-10", "2026-09-13"), plan.workouts.map { it.date })
        assertEquals(listOf("easy", "cross_train", "long"), plan.workouts.map { it.type })
        assertEquals(listOf("completed", "skipped", "planned"), plan.workouts.map { it.status })
        assertEquals(listOf(7L, null, null), plan.workouts.map { it.completedRunId })
        assertEquals("9:15-9:45", plan.workouts.first().targetPace)
        assertEquals(555.4, plan.workouts.first().paceFastSecPerMile!!, 0.0)
        assertEquals(1800L, plan.workouts[1].durationSeconds)
    }

    @Test
    fun `a backup with a plan decodes back to the same backup`() {
        val original = backupWithPlan()
        assertEquals(original, Json.decodeFromString(Backup.serializer(), BackupJson.encode(original)))
    }

    @Test
    fun `the backup file can be opened as a plan to restore it`() {
        val restored = PlanParser.parse(BackupJson.encode(backupWithPlan()), today = LocalDate.of(2026, 9, 1))

        assertEquals("Half plan", restored.name)
        assertEquals(LocalDate.of(2027, 11, 14), restored.raceDate)
        assertEquals(Units.milesToMeters(13.1), restored.raceDistanceMeters!!, 0.01)
        assertEquals(workouts.map { it.date }, restored.workouts.map { it.date })
        assertEquals(workouts.map { it.type }, restored.workouts.map { it.type })
        assertEquals(workouts.map { it.title }, restored.workouts.map { it.title })
        assertEquals(Units.milesToMeters(3.0), restored.workouts.first().distanceMeters!!, 0.01)
        assertEquals(1800L, restored.workouts[1].durationSec)
        // Done and skipped come back, so the weeks already run are not shown as missed.
        assertEquals(workouts.map { it.status }, restored.workouts.map { it.status })
        // Rounded to whole seconds on the way through the file.
        assertEquals(555.0, restored.workouts.first().paceFastSecPerMile!!, 0.0)
        assertEquals(585.0, restored.workouts.first().paceSlowSecPerMile!!, 0.0)
    }
}
