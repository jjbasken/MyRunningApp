package com.myrunningapp.data

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
import com.myrunningapp.domain.model.HealthSyncCounts
import com.myrunningapp.domain.model.HealthSyncState
import com.myrunningapp.domain.model.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory DAOs shared by the tests that exercise logic sitting on top of Room.
 *
 * Room's own behaviour is [com.myrunningapp.data.db.HealthSyncDaoTest]'s job;
 * these exist so the rules above it can be tested as plain JVM Kotlin, which is
 * how the rest of this project tests its repositories.
 */
internal class FakeRunDao : RunDao {
    val rows = linkedMapOf<Long, RunEntity>()
    private var nextId = 1L

    override fun observeAll(): Flow<List<RunEntity>> =
        flowOf(rows.values.filter { !it.isInProgress })

    override fun observeById(runId: Long): Flow<RunEntity?> = flowOf(rows[runId])
    override suspend fun getById(runId: Long): RunEntity? = rows[runId]

    override suspend fun insert(run: RunEntity): Long {
        val id = if (run.id != 0L) run.id else nextId++
        rows[id] = run.copy(id = id)
        return id
    }

    override suspend fun update(run: RunEntity) { rows[run.id] = run }
    override suspend fun delete(run: RunEntity) { rows.remove(run.id) }

    override suspend fun deleteById(runId: Long) {
        if (rows[runId]?.isInProgress == false) rows.remove(runId)
    }

    // Crash-recovery checkpointing isn't exercised by the tests that use this fake; these
    // three are no-ops purely so FakeRunDao compiles as a RunDao.
    override suspend fun insertCheckpointPoints(points: List<RunPointEntity>) = Unit
    override suspend fun insertCheckpointSplits(splits: List<SplitEntity>) = Unit
    override suspend fun clearCheckpointSplits(runId: Long) = Unit
}

internal class FakeRunPointDao : RunPointDao {
    val rows = mutableListOf<RunPointEntity>()

    override fun observeForRun(runId: Long): Flow<List<RunPointEntity>> =
        flowOf(rows.filter { it.runId == runId })

    override suspend fun getForRun(runId: Long): List<RunPointEntity> =
        rows.filter { it.runId == runId }

    override suspend fun insert(point: RunPointEntity): Long { rows += point; return 0L }
    override suspend fun insertAll(points: List<RunPointEntity>) { rows += points }
    override suspend fun countForRun(runId: Long): Int = rows.count { it.runId == runId }
}

internal class FakeSplitDao : SplitDao {
    val rows = mutableListOf<SplitEntity>()

    override fun observeForRun(runId: Long): Flow<List<SplitEntity>> =
        flowOf(rows.filter { it.runId == runId })

    override suspend fun getForRun(runId: Long): List<SplitEntity> =
        rows.filter { it.runId == runId }

    override suspend fun insert(split: SplitEntity): Long { rows += split; return 0L }
}

internal class FakeProfileDao(private val profile: Profile) : ProfileDao {
    override fun observe(): Flow<ProfileEntity?> = flowOf(ProfileEntity.fromDomain(profile))
    override suspend fun get(): ProfileEntity = ProfileEntity.fromDomain(profile)
    // No test using this fake writes a profile back; it's a no-op so FakeProfileDao compiles.
    override suspend fun upsert(profile: ProfileEntity) = Unit
}

/** Shares [FakeRunDao]'s rows, so state changes are visible through both. */
internal class FakeHealthSyncDao(private val runDao: FakeRunDao) : HealthSyncDao {
    val deletions = linkedMapOf<Long, HealthDeletionEntity>()

    override suspend fun pendingRunsAfter(
        afterStartedAt: Long,
        afterId: Long,
        limit: Int,
    ): List<RunEntity> = runDao.rows.values
        .filter { it.healthSyncState == HealthSyncState.PENDING && !it.isInProgress }
        .filter {
            val startedAt = it.startedAt.toEpochMilli()
            startedAt > afterStartedAt || (startedAt == afterStartedAt && it.id > afterId)
        }
        .sortedWith(compareBy({ it.startedAt }, { it.id }))
        .take(limit)

    override suspend fun markState(runId: Long, state: HealthSyncState) {
        runDao.rows[runId]?.let { runDao.rows[runId] = it.copy(healthSyncState = state) }
    }

    override suspend fun markPending(runId: Long) {
        runDao.rows[runId]?.let {
            runDao.rows[runId] = it.copy(
                healthSyncState = HealthSyncState.PENDING,
                healthSyncVersion = it.healthSyncVersion + 1,
            )
        }
    }

    override suspend fun markOutcome(runId: Long, state: HealthSyncState, version: Long) {
        val row = runDao.rows[runId] ?: return
        if (row.healthSyncState != HealthSyncState.PENDING) return
        if (row.healthSyncVersion != version) return
        runDao.rows[runId] = row.copy(healthSyncState = state)
    }

    override suspend fun markAllPending(): Int = mark(HealthSyncState.NOT_SYNCED)

    override suspend fun retryFailed(): Int = mark(HealthSyncState.FAILED)

    private fun mark(from: HealthSyncState): Int {
        val hits = runDao.rows.values.filter {
            it.healthSyncState == from && !it.isInProgress
        }
        hits.forEach {
            runDao.rows[it.id] = it.copy(
                healthSyncState = HealthSyncState.PENDING,
                healthSyncVersion = it.healthSyncVersion + 1,
            )
        }
        return hits.size
    }

    override suspend fun queueDeletion(deletion: HealthDeletionEntity) {
        deletions[deletion.runId] = deletion
    }

    override suspend fun pendingDeletions(): List<HealthDeletionEntity> =
        deletions.values.sortedBy { it.requestedAt }

    override suspend fun clearDeletion(runId: Long) { deletions.remove(runId) }

    override fun observeCounts(): Flow<HealthSyncCounts> = flowOf(
        runDao.rows.values.filter { !it.isInProgress }.let { finished ->
            HealthSyncCounts(
                pending = finished.count { it.healthSyncState == HealthSyncState.PENDING },
                synced = finished.count { it.healthSyncState == HealthSyncState.SYNCED },
                failed = finished.count { it.healthSyncState == HealthSyncState.FAILED },
            )
        },
    )
}
