package com.myrunningapp.data.repository

import com.myrunningapp.data.db.dao.RunDao
import com.myrunningapp.data.db.dao.RunPointDao
import com.myrunningapp.data.db.dao.SplitDao
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.RunPoint
import com.myrunningapp.domain.model.Split
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read/write access to completed runs and their tracks.
 *
 * Milestone 1 exposes the read side (history list, run detail). The tracking
 * service writes through this repository in milestone 2.
 */
@Singleton
class RunRepository @Inject constructor(
    private val runDao: RunDao,
    private val runPointDao: RunPointDao,
    private val splitDao: SplitDao,
) {
    val runs: Flow<List<Run>> =
        runDao.observeAll().map { list -> list.map(RunEntity::toDomain) }

    fun run(runId: Long): Flow<Run?> =
        runDao.observeById(runId).map { it?.toDomain() }

    fun points(runId: Long): Flow<List<RunPoint>> =
        runPointDao.observeForRun(runId).map { list -> list.map { it.toDomain() } }

    fun splits(runId: Long): Flow<List<Split>> =
        splitDao.observeForRun(runId).map { list -> list.map { it.toDomain() } }

    suspend fun getRun(runId: Long): Run? = runDao.getById(runId)?.toDomain()

    suspend fun updateRun(run: Run) = runDao.update(RunEntity.fromDomain(run))

    suspend fun deleteRun(runId: Long) = runDao.deleteById(runId)
}
