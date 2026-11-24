package com.myrunningapp.data.plan

import com.myrunningapp.data.db.dao.TrainingPlanDao
import com.myrunningapp.data.db.entity.PlanWorkoutEntity
import com.myrunningapp.data.db.entity.TrainingPlanEntity
import com.myrunningapp.domain.plan.ParsedPlan
import com.myrunningapp.domain.plan.PlanParser
import com.myrunningapp.domain.plan.PlannedWorkout
import com.myrunningapp.domain.plan.TrainingPlan
import com.myrunningapp.domain.plan.WorkoutStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** The plan in use with every one of its workouts, oldest first. */
data class ActivePlan(
    val plan: TrainingPlan,
    val workouts: List<PlannedWorkout>,
)

/**
 * The training plan: importing one, reading it back, and ticking workouts off.
 *
 * One plan at a time. Importing a new plan replaces the old one; importing the
 * rest of the same plan (an AI reply that ran out of room) merges into it.
 */
@Singleton
class TrainingPlanRepository @Inject constructor(
    private val dao: TrainingPlanDao,
    private val clock: Clock,
) : WorkoutLog {

    @OptIn(ExperimentalCoroutinesApi::class)
    val activePlan: Flow<ActivePlan?> = dao.observeActivePlan().flatMapLatest { plan ->
        if (plan == null) {
            flowOf(null)
        } else {
            dao.observeWorkouts(plan.id).map { rows ->
                ActivePlan(plan.toDomain(), rows.map { it.toDomain() })
            }
        }
    }

    /**
     * The calendar day in the phone's own time zone. The injected clock is UTC,
     * and a plan's days are local: an evening run must not land on tomorrow.
     */
    fun today(): LocalDate = clock.instant().atZone(ZoneId.systemDefault()).toLocalDate()

    /** @throws com.myrunningapp.domain.plan.PlanImportException */
    suspend fun parse(text: String): ParsedPlan = withContext(Dispatchers.Default) {
        PlanParser.parse(text, today())
    }

    suspend fun hasPlan(): Boolean = dao.getActivePlan() != null

    /** Throws away the current plan, if any, and starts this one. */
    suspend fun replaceWith(parsed: ParsedPlan): Long = dao.replacePlan(
        TrainingPlanEntity(
            name = parsed.name,
            raceDate = parsed.raceDate,
            raceDistanceMeters = parsed.raceDistanceMeters,
            importedAt = clock.instant(),
        ),
        parsed.workouts.map { it.toEntity() },
    )

    /**
     * Adds more weeks to the current plan, keeping its name and race date.
     * Falls back to [replaceWith] when there is no plan to add to.
     */
    suspend fun mergeIntoCurrent(parsed: ParsedPlan): Long {
        val current = dao.getActivePlan() ?: return replaceWith(parsed)
        dao.mergeIntoPlan(current.id, parsed.workouts.map { it.toEntity() })
        return current.id
    }

    suspend fun deletePlan() = dao.deleteAllPlans()

    suspend fun getWorkout(workoutId: Long): PlannedWorkout? = dao.getWorkout(workoutId)?.toDomain()

    suspend fun markCompleted(workoutId: Long) {
        val existing = dao.getWorkout(workoutId) ?: return
        dao.setStatus(workoutId, WorkoutStatus.COMPLETED, existing.completedRunId)
    }

    suspend fun markSkipped(workoutId: Long) = dao.setStatus(workoutId, WorkoutStatus.SKIPPED, null)

    suspend fun resetWorkout(workoutId: Long) = dao.setStatus(workoutId, WorkoutStatus.PLANNED, null)

    override suspend fun recordWorkoutRun(workoutId: Long, runId: Long) =
        dao.setStatus(workoutId, WorkoutStatus.COMPLETED, runId)

    private fun com.myrunningapp.domain.plan.ParsedWorkout.toEntity() = PlanWorkoutEntity(
        planId = 0,
        date = date,
        type = type,
        title = title,
        description = description,
        distanceMeters = distanceMeters,
        durationSec = durationSec,
        paceFastSecPerMile = paceFastSecPerMile,
        paceSlowSecPerMile = paceSlowSecPerMile,
        status = WorkoutStatus.PLANNED,
        completedRunId = null,
    )
}
