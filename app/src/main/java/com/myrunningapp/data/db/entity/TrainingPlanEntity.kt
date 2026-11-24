package com.myrunningapp.data.db.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myrunningapp.domain.plan.PlannedWorkout
import com.myrunningapp.domain.plan.TrainingPlan
import com.myrunningapp.domain.plan.WorkoutStatus
import com.myrunningapp.domain.plan.WorkoutType
import java.time.Instant
import java.time.LocalDate

@Entity(tableName = "training_plans")
data class TrainingPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val raceDate: LocalDate?,
    val raceDistanceMeters: Double?,
    val importedAt: Instant,
) {
    fun toDomain() = TrainingPlan(
        id = id,
        name = name,
        raceDate = raceDate,
        raceDistanceMeters = raceDistanceMeters,
        importedAt = importedAt,
    )
}

/**
 * One planned session.
 *
 * [completedRunId] points at the run that did it, and is cleared — not cascaded —
 * when that run is deleted: deleting a run from history should not delete the
 * plan's record that the day was meant to happen. [status] stays as it was, so a
 * workout ticked off with a since-deleted run still counts as done.
 *
 * While a coached run is recording, the workout is still [WorkoutStatus.PLANNED]
 * but already carries that run's id, so a process death mid-run can be credited
 * on recovery. [toDomain] hides the link until the workout is done: nothing above
 * the database should treat a run in progress as a finished one.
 */
@Entity(
    tableName = "plan_workouts",
    foreignKeys = [
        ForeignKey(
            entity = TrainingPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["id"],
            childColumns = ["completedRunId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("planId", "date"), Index("completedRunId")],
)
data class PlanWorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val date: LocalDate,
    val type: WorkoutType,
    val title: String,
    val description: String,
    val distanceMeters: Double?,
    val durationSec: Long?,
    val paceFastSecPerMile: Double?,
    val paceSlowSecPerMile: Double?,
    val status: WorkoutStatus,
    val completedRunId: Long?,
) {
    fun toDomain(
        completedDistanceMeters: Double? = null,
        completedPaceSecPerMile: Double? = null,
    ): PlannedWorkout {
        val done = status == WorkoutStatus.COMPLETED
        return PlannedWorkout(
            id = id,
            planId = planId,
            date = date,
            type = type,
            title = title,
            description = description,
            distanceMeters = distanceMeters,
            durationSec = durationSec,
            paceFastSecPerMile = paceFastSecPerMile,
            paceSlowSecPerMile = paceSlowSecPerMile,
            status = status,
            completedRunId = completedRunId.takeIf { done },
            completedDistanceMeters = completedDistanceMeters.takeIf { done },
            completedPaceSecPerMile = completedPaceSecPerMile.takeIf { done },
        )
    }
}

/** A workout with the headline numbers of the run that completed it, if any. */
data class PlanWorkoutWithRun(
    @Embedded val workout: PlanWorkoutEntity,
    val runDistanceMeters: Double?,
    val runPaceSecPerMile: Double?,
) {
    fun toDomain() = workout.toDomain(runDistanceMeters, runPaceSecPerMile)
}
