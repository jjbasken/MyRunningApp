package com.myrunningapp.data.repository

import com.myrunningapp.data.FakeHealthSyncDao
import com.myrunningapp.data.FakeProfileDao
import com.myrunningapp.data.FakeRunDao
import com.myrunningapp.data.FakeRunPointDao
import com.myrunningapp.data.FakeSplitDao
import com.myrunningapp.domain.calories.CalorieCalculator
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.Profile
import com.myrunningapp.domain.model.Sex
import com.myrunningapp.domain.tracking.RunSnapshot
import com.myrunningapp.domain.model.RunSessionState
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The calorie estimate reaches the database — that the run row ends up carrying a
 * number consistent with its own weight snapshot, not with whatever the profile
 * says today. The arithmetic itself is CalorieCalculatorTest's job.
 */
class RunRepositoryCaloriesTest {

    private val startedAt: Instant = Instant.parse("2026-09-03T14:00:00Z")

    private val profile = Profile(weightKg = 70.0, heightCm = 175.0, age = 35, sex = Sex.MALE)

    private val runDao = FakeRunDao()

    private fun repository(currentProfile: Profile = profile) = RunRepository(
        runDao = runDao,
        runPointDao = FakeRunPointDao(),
        splitDao = FakeSplitDao(),
        profileRepository = ProfileRepository(FakeProfileDao(currentProfile)),
        healthSyncDao = FakeHealthSyncDao(runDao),
        healthSyncScheduler = mockk(relaxed = true),
    )

    private fun snapshot(distanceMeters: Double, movingDurationSec: Long) = RunSnapshot(
        state = RunSessionState.FINISHED,
        activityType = ActivityType.RUN,
        startedAt = startedAt,
        distanceMeters = distanceMeters,
        movingDurationSec = movingDurationSec,
        elapsedDurationSec = movingDurationSec,
        avgPaceSecPerMile = 480.0,
        segmentIndex = 0,
        countdownSecondsRemaining = 0,
        lastFix = null,
        completedSplits = emptyList(),
    )

    @Test
    fun `finishing a run stores a calorie estimate`() = runTest {
        val repository = repository()
        val id = repository.startRun(ActivityType.RUN, startedAt, weightKg = 70.0)

        repository.finishRun(id, snapshot(5000.0, 1500), startedAt.plusSeconds(1500))

        assertEquals(378, runDao.getById(id)!!.calories)
    }

    @Test
    fun `the estimate uses the weight snapshot, not today's weight`() = runTest {
        // The user weighed 90 kg for the run and has since dropped to 70 kg.
        val repository = repository(currentProfile = profile.copy(weightKg = 70.0))
        val id = repository.startRun(ActivityType.RUN, startedAt, weightKg = 90.0)

        repository.finishRun(id, snapshot(5000.0, 1500), startedAt.plusSeconds(1500))

        val heavier = CalorieCalculator.calories(
            ActivityType.RUN, 5000.0, 1500, profile.copy(weightKg = 90.0),
        )
        assertEquals(heavier, runDao.getById(id)!!.calories)
        assertTrue(runDao.getById(id)!!.calories > 378)
    }

    @Test
    fun `correcting a run to a walk re-estimates its calories`() = runTest {
        val repository = repository()
        val id = repository.startRun(ActivityType.RUN, startedAt, weightKg = 70.0)
        repository.finishRun(id, snapshot(5000.0, 1500), startedAt.plusSeconds(1500))
        val asRun = runDao.getById(id)!!.calories

        repository.updateActivityType(id, ActivityType.WALK)

        val row = runDao.getById(id)!!
        assertEquals(ActivityType.WALK, row.activityType)
        assertTrue("walking the same route should cost less", row.calories < asRun)
    }

    @Test
    fun `updating a run that no longer exists is a no-op`() = runTest {
        repository().updateActivityType(404L, ActivityType.WALK)
        assertEquals(0, runDao.rows.size)
    }

    @Test
    fun `active run cannot be deleted or edited through repository`() = runTest {
        val repository = repository()
        val id = repository.startRun(ActivityType.RUN, startedAt, 70.0)
        repository.deleteRun(id)
        repository.updateActivityType(id, ActivityType.WALK)
        repository.updateRun(runDao.getById(id)!!.toDomain().copy(distanceMeters = 999.0))
        val row = runDao.getById(id)!!
        assertTrue(row.isInProgress)
        assertEquals(ActivityType.RUN, row.activityType)
        assertEquals(0.0, row.distanceMeters, 0.0)
        repository.finishRun(id, snapshot(5000.0, 1500), startedAt.plusSeconds(1500))
        repository.deleteRun(id)
        assertEquals(null, runDao.getById(id))
    }
}
