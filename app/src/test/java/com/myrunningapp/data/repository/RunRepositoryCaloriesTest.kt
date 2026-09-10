package com.myrunningapp.data.repository

import com.myrunningapp.data.db.dao.HealthSyncDao
import com.myrunningapp.data.db.dao.ProfileDao
import com.myrunningapp.data.db.dao.RunDao
import com.myrunningapp.data.db.dao.RunPointDao
import com.myrunningapp.data.db.dao.SplitDao
import com.myrunningapp.data.db.entity.HealthDeletionEntity
import com.myrunningapp.data.db.entity.ProfileEntity
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.data.db.entity.RunPointEntity
import com.myrunningapp.data.db.entity.SplitEntity
import com.myrunningapp.domain.calories.CalorieCalculator
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthSyncCounts
import com.myrunningapp.domain.model.HealthSyncState
import com.myrunningapp.domain.model.Profile
import com.myrunningapp.domain.model.Sex
import com.myrunningapp.domain.tracking.RunSnapshot
import com.myrunningapp.domain.model.RunSessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

    // --- fakes ---------------------------------------------------------------

    private class FakeRunDao : RunDao {
        val rows = mutableMapOf<Long, RunEntity>()
        private var nextId = 1L

        override fun observeAll(): Flow<List<RunEntity>> = flowOf(rows.values.filter { !it.isInProgress })
        override fun observeById(runId: Long): Flow<RunEntity?> = flowOf(rows[runId])
        override suspend fun getById(runId: Long): RunEntity? = rows[runId]
        override suspend fun insert(run: RunEntity): Long {
            val id = nextId++
            rows[id] = run.copy(id = id)
            return id
        }
        override suspend fun update(run: RunEntity) { rows[run.id] = run }
        override suspend fun delete(run: RunEntity) { rows.remove(run.id) }
        override suspend fun deleteById(runId: Long) {
            if (rows[runId]?.isInProgress == false) rows.remove(runId)
        }
        override suspend fun insertCheckpointPoints(points: List<RunPointEntity>) = Unit
        override suspend fun insertCheckpointSplits(splits: List<SplitEntity>) = Unit
        override suspend fun clearCheckpointSplits(runId: Long) = Unit
    }

    private class FakeRunPointDao : RunPointDao {
        override fun observeForRun(runId: Long): Flow<List<RunPointEntity>> = flowOf(emptyList())
        override suspend fun getForRun(runId: Long): List<RunPointEntity> = emptyList()
        override suspend fun insert(point: RunPointEntity): Long = 0L
        override suspend fun insertAll(points: List<RunPointEntity>) = Unit
        override suspend fun countForRun(runId: Long): Int = 0
    }

    private class FakeSplitDao : SplitDao {
        override fun observeForRun(runId: Long): Flow<List<SplitEntity>> = flowOf(emptyList())
        override suspend fun getForRun(runId: Long): List<SplitEntity> = emptyList()
        override suspend fun insert(split: SplitEntity): Long = 0L
    }

    private class FakeProfileDao(private val profile: Profile) : ProfileDao {
        override fun observe(): Flow<ProfileEntity?> = flowOf(ProfileEntity.fromDomain(profile))
        override suspend fun get(): ProfileEntity = ProfileEntity.fromDomain(profile)
        override suspend fun upsert(profile: ProfileEntity) = Unit
    }

    /** Not this test's concern — calorie math is — so it just has to compile and not lie. */
    private class FakeHealthSyncDao(private val runDao: FakeRunDao) : HealthSyncDao {
        private val deletions = mutableMapOf<Long, HealthDeletionEntity>()

        override suspend fun pendingRuns(limit: Int): List<RunEntity> =
            runDao.rows.values
                .filter { it.healthSyncState == HealthSyncState.PENDING && !it.isInProgress }
                .sortedBy { it.startedAt }
                .take(limit)

        override suspend fun markState(runId: Long, state: HealthSyncState) {
            runDao.rows[runId]?.let { runDao.rows[runId] = it.copy(healthSyncState = state) }
        }

        override suspend fun markAllPending(): Int = 0
        override suspend fun retryFailed(): Int = 0

        override suspend fun queueDeletion(deletion: HealthDeletionEntity) {
            deletions[deletion.runId] = deletion
        }

        override suspend fun pendingDeletions(): List<HealthDeletionEntity> =
            deletions.values.sortedBy { it.requestedAt }

        override suspend fun clearDeletion(runId: Long) { deletions.remove(runId) }

        override fun observeCounts(): Flow<HealthSyncCounts> =
            flowOf(HealthSyncCounts(pending = 0, synced = 0, failed = 0))
    }
}
