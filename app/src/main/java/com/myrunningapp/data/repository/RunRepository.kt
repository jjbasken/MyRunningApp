package com.myrunningapp.data.repository

import com.myrunningapp.data.db.dao.RunDao
import com.myrunningapp.data.db.dao.RunPointDao
import com.myrunningapp.data.db.dao.SplitDao
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.data.db.entity.RunPointEntity
import com.myrunningapp.data.db.entity.SplitEntity
import com.myrunningapp.data.location.RunRecorder
import com.myrunningapp.domain.calories.CalorieCalculator
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.Profile
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
    private val profileRepository: ProfileRepository,
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

    /**
     * Corrects a run recorded as the wrong activity, re-estimating its calories:
     * the same distance and time cost noticeably more running than walking, so
     * leaving the old number would contradict the label right beside it.
     */
    suspend fun updateActivityType(runId: Long, activityType: ActivityType) {
        val existing = runDao.getById(runId) ?: return
        runDao.update(
            existing.copy(
                activityType = activityType,
                calories = estimateCalories(
                    activityType = activityType,
                    distanceMeters = existing.distanceMeters,
                    movingDurationSec = existing.movingDurationSec,
                    weightKgAtRun = existing.weightKgAtRun,
                ),
            ),
        )
    }

    /** Deletes a run; its points and splits go with it via `ON DELETE CASCADE`. */
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

    /** Fills in the summary, including the calorie estimate, once a run is over. */
    override suspend fun finishRun(runId: Long, snapshot: RunSnapshot, endedAt: Instant) {
        val existing = runDao.getById(runId) ?: return
        runDao.update(
            existing.copy(
                endedAt = endedAt,
                distanceMeters = snapshot.distanceMeters,
                movingDurationSec = snapshot.movingDurationSec,
                elapsedDurationSec = snapshot.elapsedDurationSec,
                avgPaceSecPerMile = snapshot.avgPaceSecPerMile.takeIf { it.isFinite() } ?: 0.0,
                calories = estimateCalories(
                    activityType = existing.activityType,
                    distanceMeters = snapshot.distanceMeters,
                    movingDurationSec = snapshot.movingDurationSec,
                    weightKgAtRun = existing.weightKgAtRun,
                ),
            ),
        )
    }

    /**
     * Estimates calories against the run's own **weight snapshot** rather than
     * today's weight, so a profile edit never rewrites past runs. Height, age and
     * sex — which only tune the resting term — are read live; they change rarely
     * enough that snapshotting all four would be more bookkeeping than it is worth.
     */
    private suspend fun estimateCalories(
        activityType: ActivityType,
        distanceMeters: Double,
        movingDurationSec: Long,
        weightKgAtRun: Double,
    ): Int {
        val profile = profileRepository.get()
        return CalorieCalculator.calories(
            activityType = activityType,
            distanceMeters = distanceMeters,
            movingDurationSec = movingDurationSec,
            profile = Profile(
                weightKg = weightKgAtRun.takeIf { it > 0.0 } ?: profile.weightKg,
                heightCm = profile.heightCm,
                age = profile.age,
                sex = profile.sex,
            ),
        )
    }

    override suspend fun discardRun(runId: Long) = runDao.deleteById(runId)
}
