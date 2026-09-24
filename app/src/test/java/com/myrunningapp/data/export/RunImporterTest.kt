package com.myrunningapp.data.export

import com.myrunningapp.data.FakeHealthSyncDao
import com.myrunningapp.data.FakeProfileDao
import com.myrunningapp.data.FakeRunDao
import com.myrunningapp.data.FakeRunPointDao
import com.myrunningapp.data.FakeSplitDao
import com.myrunningapp.data.health.HealthSyncScheduler
import com.myrunningapp.data.repository.ProfileRepository
import com.myrunningapp.data.repository.RunRepository
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthSyncState
import com.myrunningapp.domain.model.Profile
import com.myrunningapp.domain.model.Sex
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RunImporterTest {

    private val profile = Profile(weightKg = 68.0, heightCm = 170.0, age = 40, sex = Sex.FEMALE)
    private val runDao = FakeRunDao()
    private val scheduler = mockk<HealthSyncScheduler>(relaxed = true)
    private val importer = RunImporter(
        RunRepository(
            runDao = runDao,
            runPointDao = FakeRunPointDao(),
            splitDao = FakeSplitDao(),
            profileRepository = ProfileRepository(FakeProfileDao(profile)),
            healthSyncDao = FakeHealthSyncDao(runDao),
            healthSyncScheduler = scheduler,
        ),
    )

    /** 2 km due north at 3 m/s, one fix a second, as one track segment. */
    private fun gpx(type: String? = "walking", startHour: Int = 6, startOffsetSec: Long = 0): ByteArray {
        val metersPerDegree = Math.toRadians(1.0) * 6_371_008.8
        val points = (0..667).joinToString("\n") { i ->
            val lat = String.format(java.util.Locale.US, "%.8f", i * 3.0 / metersPerDegree)
            val time = java.time.Instant.parse("2026-09-01T%02d:00:00Z".format(startHour)).plusSeconds(startOffsetSec + i)
            """<trkpt lat="$lat" lon="0"><ele>10</ele><time>$time</time></trkpt>"""
        }
        val typeElement = type?.let { "<type>$it</type>" }.orEmpty()
        // Concatenated, not trimIndent()ed: the unindented point lines would leave
        // whitespace ahead of the XML declaration, which no parser accepts.
        return (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n" +
                "<trk>$typeElement<trkseg>\n$points\n</trkseg></trk></gpx>\n"
            ).toByteArray()
    }

    @Test
    fun `an import lands in history with its route, splits and calories`() = runTest {
        val id = importer.importGpx(gpx())

        val run = runDao.getById(id)!!
        assertEquals(ActivityType.WALK, run.activityType)
        assertFalse(run.isInProgress)
        assertEquals(2001.0, run.distanceMeters, 0.5)
        assertEquals(667, run.movingDurationSec)
        assertEquals(68.0, run.weightKgAtRun, 0.0)
        assertTrue(run.calories > 0)
        assertEquals(668, runDao.points.count { it.runId == id })
        assertEquals(listOf(1, 2), runDao.splits.filter { it.runId == id }.map { it.splitNumber })
    }

    @Test
    fun `an import is queued for Health Connect like any finished run`() = runTest {
        val id = importer.importGpx(gpx())

        assertEquals(HealthSyncState.PENDING, runDao.getById(id)!!.healthSyncState)
        verify { scheduler.requestSync() }
    }

    @Test
    fun `a file that does not say falls back to the given type`() = runTest {
        val id = importer.importGpx(gpx(type = null), fallbackType = ActivityType.BIKE)

        assertEquals(ActivityType.BIKE, runDao.getById(id)!!.activityType)
    }

    @Test
    fun `an activity starting the moment another ended is not a duplicate`() = runTest {
        // The first file runs 06:00:00 to 06:11:07; this one starts on that last second.
        importer.importGpx(gpx())
        importer.importGpx(gpx(startHour = 6, startOffsetSec = 667))

        assertEquals(2, runDao.rows.size)
    }

    @Test
    fun `importing the same file twice is refused`() = runTest {
        importer.importGpx(gpx())

        try {
            importer.importGpx(gpx())
            fail("expected a duplicate to be refused")
        } catch (e: GpxImportException) {
            assertEquals(GpxImportFailure.ALREADY_IMPORTED, e.failure)
        }
        assertEquals(1, runDao.rows.size)
        // A different morning is a different activity.
        importer.importGpx(gpx(startHour = 9))
        assertEquals(2, runDao.rows.size)
    }
}
