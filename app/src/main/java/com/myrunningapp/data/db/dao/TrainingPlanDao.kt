package com.myrunningapp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.myrunningapp.data.db.entity.PlanWorkoutEntity
import com.myrunningapp.data.db.entity.PlanWorkoutWithRun
import com.myrunningapp.data.db.entity.TrainingPlanEntity
import com.myrunningapp.domain.plan.WorkoutStatus
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface TrainingPlanDao {

    /** The plan in use. Only one is kept, but the newest wins if that ever slips. */
    @Query("SELECT * FROM training_plans ORDER BY id DESC LIMIT 1")
    fun observeActivePlan(): Flow<TrainingPlanEntity?>

    @Query("SELECT * FROM training_plans ORDER BY id DESC LIMIT 1")
    suspend fun getActivePlan(): TrainingPlanEntity?

    @Query(
        """
        SELECT plan_workouts.*,
               runs.distanceMeters AS runDistanceMeters,
               runs.avgPaceSecPerMile AS runPaceSecPerMile
        FROM plan_workouts
        LEFT JOIN runs ON runs.id = plan_workouts.completedRunId
        WHERE plan_workouts.planId = :planId
        ORDER BY plan_workouts.date, plan_workouts.id
        """,
    )
    fun observeWorkouts(planId: Long): Flow<List<PlanWorkoutWithRun>>

    @Query("SELECT * FROM plan_workouts WHERE id = :workoutId")
    suspend fun getWorkout(workoutId: Long): PlanWorkoutEntity?

    @Insert
    suspend fun insertPlan(plan: TrainingPlanEntity): Long

    @Insert
    suspend fun insertWorkouts(workouts: List<PlanWorkoutEntity>)

    @Query("DELETE FROM training_plans")
    suspend fun deleteAllPlans()

    @Query("UPDATE plan_workouts SET status = :status, completedRunId = :runId WHERE id = :workoutId")
    suspend fun setStatus(workoutId: Long, status: WorkoutStatus, runId: Long?)

    /**
     * Clears not-yet-done workouts on [epochDays], so a merged-in chunk replaces
     * them. Days rather than [LocalDate]s: the `date` column stores epoch days.
     */
    @Query("DELETE FROM plan_workouts WHERE planId = :planId AND status = 'PLANNED' AND date IN (:epochDays)")
    suspend fun deletePlannedOn(planId: Long, epochDays: List<Long>)

    /** Workouts on [epochDays] that are done or skipped, which a merged-in chunk must not duplicate. */
    @Query("SELECT * FROM plan_workouts WHERE planId = :planId AND status != 'PLANNED' AND date IN (:epochDays)")
    suspend fun getSettledOn(planId: Long, epochDays: List<Long>): List<PlanWorkoutEntity>

    /** Swaps the whole plan out in one go, so a failed import never leaves half of one. */
    @Transaction
    suspend fun replacePlan(plan: TrainingPlanEntity, workouts: List<PlanWorkoutEntity>): Long {
        deleteAllPlans()
        val planId = insertPlan(plan)
        insertWorkouts(workouts.map { it.copy(planId = planId) })
        return planId
    }

    /**
     * Adds a later chunk of the same plan, returning how many workouts it added.
     *
     * Planned workouts on a date the chunk covers give way to the chunk's version.
     * Workouts already done or skipped are kept, and each one stands in for one of
     * the chunk's workouts on its date — the same type first, else any — so
     * re-importing weeks already under way does not put a fresh copy of a finished
     * run back on the calendar, to be nagged about as missed and coached again.
     * A chunk with more workouts on a day than were settled (a double) keeps the rest.
     */
    @Transaction
    suspend fun mergeIntoPlan(planId: Long, workouts: List<PlanWorkoutEntity>): Int {
        val days = workouts.map { it.date.toEpochDay() }.distinct()
        val settled = days.chunked(SQLITE_MAX_ARGS).flatMap { getSettledOn(planId, it) }.groupBy { it.date }
        days.chunked(SQLITE_MAX_ARGS).forEach { deletePlannedOn(planId, it) }

        val toInsert = workouts.groupBy { it.date }.flatMap { (date, chunkDay) ->
            val unmatched = chunkDay.toMutableList()
            var settledWithoutMatch = 0
            for (done in settled[date].orEmpty()) {
                val sameType = unmatched.indexOfFirst { it.type == done.type }
                if (sameType >= 0) {
                    unmatched.removeAt(sameType)
                } else {
                    settledWithoutMatch++
                }
            }
            // Settled workouts whose type the chunk changed still take a slot each.
            unmatched.drop(settledWithoutMatch)
        }
        insertWorkouts(toInsert.map { it.copy(planId = planId) })
        return toInsert.size
    }
}

/** Well under SQLite's bound-parameter limit, with room for `planId`. */
private const val SQLITE_MAX_ARGS = 500
