package com.myrunningapp.data.repository

import com.myrunningapp.data.db.dao.RunDao
import com.myrunningapp.data.db.dao.RunPointDao
import com.myrunningapp.data.db.dao.SplitDao
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.data.db.entity.RunPointEntity
import com.myrunningapp.data.db.entity.SplitEntity
import com.myrunningapp.data.location.RunRecorder
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.RunPoint
import com.myrunningapp.domain.model.Split
import com.myrunningapp.domain.tracking.MileSplit
import com.myrunningapp.domain.tracking.RunSnapshot
import com.myrunningapp.domain.tracking.TrackedPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read/write access to completed runs and their tracks.
 *
 * The read side feeds the history list and the run detail screen; the write side
 * is [RunRecorder], which [com.myrunningapp.data.location.RunTracker] calls as a
 * run unfolds.
 */
@Singleton
class RunRepository @Inject constructor(
    private val runDao: RunDao,
    private val runPointDao: RunPointDao,
    private val splitDao: SplitDao,
) : RunRecorder {
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

    // --- RunRecorder: the tracking service's write path -----------------------

    /**
     * Opens a run row as soon as tracking starts, with the summary fields still
     * empty. Writing the row up front means the points streaming in have a run to
     * belong to, so a crash mid-run leaves the track recorded rather than lost.
     */
    override suspend fun startRun(
        activityType: ActivityType,
        startedAt: Instant,
        weightKg: Double,
    ): Long = runDao.insert(
        RunEntity(
            startedAt = startedAt,
            endedAt = startedAt,
            activityType = activityType,
            distanceMeters = 0.0,
            movingDurationSec = 0L,
            elapsedDurationSec = 0L,
            // Not NaN: SQLite has no such value and would store it as NULL,
            // which Room cannot read back into a non-null Double.
            avgPaceSecPerMile = 0.0,
            calories = 0,
            weightKgAtRun = weightKg,
        ),
    )

    override suspend fun recordPoints(runId: Long, points: List<TrackedPoint>) {
        if (points.isEmpty()) return
        runPointDao.insertAll(
            points.map { tracked ->
                RunPointEntity(
                    runId = runId,
                    timestamp = tracked.fix.timestamp,
                    latitude = tracked.fix.latitude,
                    longitude = tracked.fix.longitude,
                    altitudeMeters = tracked.fix.altitudeMeters,
                    accuracyMeters = tracked.fix.accuracyMeters,
                    segmentIndex = tracked.segmentIndex,
                )
            },
        )
    }

    override suspend fun recordSplit(runId: Long, split: MileSplit) {
        splitDao.insert(
            SplitEntity(
                runId = runId,
                splitNumber = split.splitNumber,
                distanceMeters = split.distanceMeters,
                durationSec = split.durationSec,
                paceSecPerMile = split.paceSecPerMile,
            ),
        )
    }

    /**
     * Fills in the summary. Calories stay at zero until milestone 5 wires up the
     * calculator; the weight snapshot it will need is already on the row.
     */
    override suspend fun finishRun(runId: Long, snapshot: RunSnapshot, endedAt: Instant) {
        val existing = runDao.getById(runId) ?: return
        runDao.update(
            existing.copy(
                endedAt = endedAt,
                distanceMeters = snapshot.distanceMeters,
                movingDurationSec = snapshot.movingDurationSec,
                elapsedDurationSec = snapshot.elapsedDurationSec,
                avgPaceSecPerMile = snapshot.avgPaceSecPerMile.takeIf { it.isFinite() } ?: 0.0,
            ),
        )
    }

    override suspend fun discardRun(runId: Long) = runDao.deleteById(runId)
}
