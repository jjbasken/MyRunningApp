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

    /** Swaps the whole plan out in one go, so a failed import never leaves half of one. */
    @Transaction
    suspend fun replacePlan(plan: TrainingPlanEntity, workouts: List<PlanWorkoutEntity>): Long {
        deleteAllPlans()
        val planId = insertPlan(plan)
        insertWorkouts(workouts.map { it.copy(planId = planId) })
        return planId
    }

    /**
     * Adds a later chunk of the same plan. Workouts already done on a date are kept;
     * planned ones on a date the chunk covers give way to the chunk's version.
     */
    @Transaction
    suspend fun mergeIntoPlan(planId: Long, workouts: List<PlanWorkoutEntity>) {
        workouts.map { it.date.toEpochDay() }.distinct().chunked(SQLITE_MAX_ARGS).forEach { deletePlannedOn(planId, it) }
        insertWorkouts(workouts.map { it.copy(planId = planId) })
    }
}

/** Well under SQLite's bound-parameter limit, with room for `planId`. */
private const val SQLITE_MAX_ARGS = 500
