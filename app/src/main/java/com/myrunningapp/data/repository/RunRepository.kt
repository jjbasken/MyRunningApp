package com.myrunningapp.data.repository

import com.myrunningapp.data.db.dao.HealthSyncDao
import com.myrunningapp.data.db.dao.RunDao
import com.myrunningapp.data.db.dao.RunPointDao
import com.myrunningapp.data.db.dao.SplitDao
import com.myrunningapp.data.db.entity.HealthDeletionEntity
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.data.db.entity.RunPointEntity
import com.myrunningapp.data.db.entity.SplitEntity
import com.myrunningapp.data.health.HealthSyncScheduler
import com.myrunningapp.data.location.RunRecorder
import com.myrunningapp.domain.Units
import com.myrunningapp.domain.calories.CalorieCalculator
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthSyncState
import com.myrunningapp.domain.model.Profile
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.RunPoint
import com.myrunningapp.domain.model.Split
import com.myrunningapp.domain.tracking.MileSplit
import com.myrunningapp.domain.tracking.RunSnapshot
import com.myrunningapp.domain.tracking.TrackedPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
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
    private val healthSyncDao: HealthSyncDao,
    private val healthSyncScheduler: HealthSyncScheduler,
    private val clock: Clock = Clock.systemUTC(),
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

    /**
     * Applies an edit to a finished run.
     *
     * Builds the new row from the existing one via `copy`, the way
     * [updateActivityType] does, rather than from the [Run] alone: a [Run]
     * carries none of the health-sync columns, so rebuilding the row from it
     * would reset a synced run to `NOT_SYNCED` while it is still present in
     * Health Connect, orphaning it on a later delete. Any edit can change a
     * published field, so it is also re-queued for a rewrite.
     */
    suspend fun updateRun(run: Run) {
        val existing = runDao.getById(run.id) ?: return
        if (existing.isInProgress) return
        runDao.update(
            existing.copy(
                startedAt = run.startedAt,
                endedAt = run.endedAt,
                activityType = run.activityType,
                distanceMeters = run.distanceMeters,
                movingDurationSec = run.movingDurationSec,
                elapsedDurationSec = run.elapsedDurationSec,
                avgPaceSecPerMile = run.avgPaceSecPerMile,
                calories = run.calories,
                weightKgAtRun = run.weightKgAtRun,
                wasRecovered = run.wasRecovered,
            ),
        )
        healthSyncDao.markPending(run.id)
        healthSyncScheduler.requestSync()
    }

    /**
     * Corrects a run recorded as the wrong activity, re-estimating its calories:
     * the same distance and time cost noticeably more running than walking, so
     * leaving the old number would contradict the label right beside it.
     */
    suspend fun updateActivityType(runId: Long, activityType: ActivityType) {
        val existing = runDao.getById(runId) ?: return
        if (existing.isInProgress) return
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
        // The label and the published workout must agree, so a correction is a rewrite.
        healthSyncDao.markPending(runId)
        healthSyncScheduler.requestSync()
    }

    /**
     * Deletes a run; its points and splits go with it via `ON DELETE CASCADE`.
     *
     * A run that reached Health Connect — or that *may* have, because the
     * process died between the write succeeding and the row being marked
     * `SYNCED` — leaves a deletion behind in the outbox, because the row that
     * would otherwise have remembered it is about to be gone. Queuing on every
     * state but `NOT_SYNCED` is deliberately broader than "was `SYNCED`": the
     * engine already treats a delete of a client id that was never written as
     * harmless (a `Rejected` delete just stops asking), so over-queuing costs
     * nothing, while under-queuing orphans a workout with no record it existed.
     * A run that never got queued at all needs no such note.
     */
    suspend fun deleteRun(runId: Long) {
        val existing = runDao.getById(runId) ?: return
        if (existing.healthSyncState != HealthSyncState.NOT_SYNCED) {
            healthSyncDao.queueDeletion(
                HealthDeletionEntity(runId = runId, requestedAt = clock.instant()),
            )
            healthSyncScheduler.requestSync()
        }
        runDao.deleteById(runId)
    }

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
            isInProgress = true,
        ),
    )

    override suspend fun checkpoint(
        runId: Long,
        points: List<TrackedPoint>,
        snapshot: RunSnapshot,
        at: Instant,
    ) {
        val existing = runDao.getById(runId) ?: return
        val splits = snapshot.completedSplits.map { split ->
            SplitEntity(
                runId = runId, splitNumber = split.splitNumber,
                distanceMeters = split.distanceMeters, durationSec = split.durationSec,
                paceSecPerMile = split.paceSecPerMile,
            )
        }.toMutableList()
        // Keep the unfinished mile too, so a recovered activity has a complete splits table.
        val remainder = snapshot.distanceMeters - splits.sumOf { it.distanceMeters }
        if (remainder >= 1.0) {
            val duration = (snapshot.movingDurationSec -
                (snapshot.completedSplits.lastOrNull()?.cumulativeMovingSec ?: 0L)).coerceAtLeast(0)
            splits += SplitEntity(
                runId = runId, splitNumber = splits.size + 1, distanceMeters = remainder,
                durationSec = duration, paceSecPerMile = Units.paceSecPerMile(remainder, duration),
            )
        }
        runDao.checkpoint(
            existing.copy(
                endedAt = at,
                distanceMeters = snapshot.distanceMeters,
                movingDurationSec = snapshot.movingDurationSec,
                elapsedDurationSec = snapshot.elapsedDurationSec,
                avgPaceSecPerMile = snapshot.avgPaceSecPerMile.takeIf { it.isFinite() } ?: 0.0,
                calories = estimateCalories(existing.activityType, snapshot.distanceMeters,
                    snapshot.movingDurationSec, existing.weightKgAtRun),
            ),
            points.map { tracked ->
                RunPointEntity(
                    runId = runId, timestamp = tracked.fix.timestamp,
                    latitude = tracked.fix.latitude, longitude = tracked.fix.longitude,
                    altitudeMeters = tracked.fix.altitudeMeters,
                    accuracyMeters = tracked.fix.accuracyMeters, segmentIndex = tracked.segmentIndex,
                )
            },
            splits,
        )
    }

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
                isInProgress = false,
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
        // The run is only worth publishing once it is complete, so the outbox is
        // marked here rather than at startRun.
        healthSyncDao.markPending(runId)
        healthSyncScheduler.requestSync()
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
