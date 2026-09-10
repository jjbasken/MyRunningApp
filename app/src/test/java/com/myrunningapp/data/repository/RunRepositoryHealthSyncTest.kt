package com.myrunningapp.data.repository

import com.myrunningapp.data.FakeHealthSyncDao
import com.myrunningapp.data.FakeProfileDao
import com.myrunningapp.data.FakeRunDao
import com.myrunningapp.data.FakeRunPointDao
import com.myrunningapp.data.FakeSplitDao
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthSyncState
import com.myrunningapp.domain.model.Profile
import com.myrunningapp.domain.model.RunSessionState
import com.myrunningapp.domain.model.Sex
import com.myrunningapp.domain.tracking.RunSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RunRepositoryHealthSyncTest {

    private val t0: Instant = Instant.parse("2026-09-09T12:00:00Z")
    private val runDao = FakeRunDao()
    private val healthSyncDao = FakeHealthSyncDao(runDao)

    private val repository = RunRepository(
        runDao = runDao,
        runPointDao = FakeRunPointDao(),
        splitDao = FakeSplitDao(),
        profileRepository = ProfileRepository(
            FakeProfileDao(Profile(weightKg = 70.0, heightCm = 175.0, age = 35, sex = Sex.MALE)),
        ),
        healthSyncDao = healthSyncDao,
        clock = Clock.fixed(t0, ZoneOffset.UTC),
    )

    /**
     * Match this to the real [RunSnapshot] — read
     * `app/src/main/java/com/myrunningapp/domain/tracking/` and fill every
     * parameter it declares. The values below only have to be self-consistent.
     */
    private fun snapshot() = RunSnapshot(
        state = RunSessionState.FINISHED,
        activityType = ActivityType.RUN,
        startedAt = t0,
        distanceMeters = 1609.34,
        movingDurationSec = 600,
        elapsedDurationSec = 600,
        avgPaceSecPerMile = 600.0,
        segmentIndex = 0,
        countdownSecondsRemaining = 0,
        lastFix = null,
        completedSplits = emptyList(),
    )

    private suspend fun finishedRun(): Long {
        val id = repository.startRun(ActivityType.RUN, t0, weightKg = 70.0)
        repository.finishRun(runId = id, snapshot = snapshot(), endedAt = t0.plusSeconds(600))
        return id
    }

    @Test
    fun `finishing a run queues it for Health Connect`() = runTest {
        val id = finishedRun()

        assertEquals(HealthSyncState.PENDING, runDao.rows[id]!!.healthSyncState)
    }

    @Test
    fun `an in-progress run is not queued`() = runTest {
        val id = repository.startRun(ActivityType.RUN, t0, weightKg = 70.0)

        assertEquals(HealthSyncState.NOT_SYNCED, runDao.rows[id]!!.healthSyncState)
    }

    @Test
    fun `correcting the activity type queues a rewrite`() = runTest {
        val id = finishedRun()
        healthSyncDao.markState(id, HealthSyncState.SYNCED)

        repository.updateActivityType(id, ActivityType.WALK)

        assertEquals(HealthSyncState.PENDING, runDao.rows[id]!!.healthSyncState)
    }

    @Test
    fun `deleting a synced run queues the deletion`() = runTest {
        val id = finishedRun()
        healthSyncDao.markState(id, HealthSyncState.SYNCED)

        repository.deleteRun(id)

        assertEquals(listOf(id), healthSyncDao.pendingDeletions().map { it.runId })
        assertEquals(t0, healthSyncDao.pendingDeletions().single().requestedAt)
    }

    @Test
    fun `deleting a run that was never synced queues nothing`() = runTest {
        val id = finishedRun()

        repository.deleteRun(id)

        assertTrue(healthSyncDao.pendingDeletions().isEmpty())
    }

    @Test
    fun `discarding an in-progress run queues nothing`() = runTest {
        val id = repository.startRun(ActivityType.RUN, t0, weightKg = 70.0)

        repository.discardRun(id)

        assertTrue(healthSyncDao.pendingDeletions().isEmpty())
    }

    @Test
    fun `retryFailed does not promote a FAILED run that is still in progress`() = runTest {
        // The real query is `WHERE healthSyncState = 'FAILED' AND isInProgress = 0` —
        // an in-progress run must never be picked up here, no matter its sync state.
        val finishedId = finishedRun()
        healthSyncDao.markState(finishedId, HealthSyncState.FAILED)

        val inProgressId = repository.startRun(ActivityType.RUN, t0, weightKg = 70.0)
        runDao.rows[inProgressId] = runDao.rows[inProgressId]!!.copy(
            healthSyncState = HealthSyncState.FAILED,
        )
        assertTrue(runDao.rows[inProgressId]!!.isInProgress)

        val promoted = healthSyncDao.retryFailed()

        assertEquals(1, promoted)
        assertEquals(HealthSyncState.PENDING, runDao.rows[finishedId]!!.healthSyncState)
        assertEquals(HealthSyncState.FAILED, runDao.rows[inProgressId]!!.healthSyncState)
    }
}
