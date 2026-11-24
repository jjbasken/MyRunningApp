package com.myrunningapp.data.plan

/**
 * Where [com.myrunningapp.data.location.RunTracker] reports that a coached run
 * finished, so the plan can tick the workout off.
 *
 * An interface so the tracker's tests need no database, and so a run with no
 * plan behind it costs nothing.
 */
interface WorkoutLog {
    suspend fun recordWorkoutRun(workoutId: Long, runId: Long)

    object None : WorkoutLog {
        override suspend fun recordWorkoutRun(workoutId: Long, runId: Long) = Unit
    }
}
