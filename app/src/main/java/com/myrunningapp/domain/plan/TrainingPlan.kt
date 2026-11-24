package com.myrunningapp.domain.plan

import java.time.Instant
import java.time.LocalDate

/**
 * What kind of session a plan asks for.
 *
 * The coach only talks during the ones that are runs ([isRun]); a rest day or a
 * cross-training day is on the calendar so the week reads honestly, but the
 * tracker has nothing to measure against it.
 */
enum class WorkoutType(val isRun: Boolean) {
    EASY(isRun = true),
    LONG(isRun = true),
    TEMPO(isRun = true),
    INTERVALS(isRun = true),
    RECOVERY(isRun = true),
    RACE(isRun = true),
    CROSS_TRAIN(isRun = false),
    REST(isRun = false),
    OTHER(isRun = true),
}

/** Where a planned workout stands. */
enum class WorkoutStatus {
    PLANNED,

    /** Done — usually with a recorded run linked, but it can also be ticked off by hand. */
    COMPLETED,

    /** Deliberately not done. Kept apart from "missed" so the coach does not nag about it. */
    SKIPPED,
}

/** The plan as a whole: one at a time, imported from an AI-written file. */
data class TrainingPlan(
    val id: Long,
    val name: String,
    val raceDate: LocalDate?,
    val raceDistanceMeters: Double?,
    val importedAt: Instant,
)

/**
 * One day's session.
 *
 * Every target is optional because plans describe workouts unevenly — a long run
 * by distance, a tempo run by time, a recovery jog by feel. Paces are seconds per
 * mile, like the rest of the app; [paceFastSecPerMile] is the smaller number.
 */
data class PlannedWorkout(
    val id: Long,
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
    /** Distance of the linked run, when there is one — for the plan's progress numbers. */
    val completedDistanceMeters: Double? = null,
    /** Average pace of the linked run, when there is one — for the coach's advice. */
    val completedPaceSecPerMile: Double? = null,
) {
    val hasPaceTarget: Boolean
        get() = paceFastSecPerMile != null && paceSlowSecPerMile != null
}

/** A plan read out of a file, before it has database ids. */
data class ParsedPlan(
    val name: String,
    val raceDate: LocalDate?,
    val raceDistanceMeters: Double?,
    val workouts: List<ParsedWorkout>,
) {
    val firstDate: LocalDate get() = workouts.minOf { it.date }
    val lastDate: LocalDate get() = workouts.maxOf { it.date }
}

data class ParsedWorkout(
    val date: LocalDate,
    val type: WorkoutType,
    val title: String,
    val description: String,
    val distanceMeters: Double?,
    val durationSec: Long?,
    val paceFastSecPerMile: Double?,
    val paceSlowSecPerMile: Double?,
    /**
     * PLANNED for anything an assistant writes. A plan restored from the app's own
     * backup brings back which workouts were done or skipped, so the weeks already
     * run do not come back looking missed.
     */
    val status: WorkoutStatus = WorkoutStatus.PLANNED,
)
