package com.myrunningapp.data.plan

/**
 * Where [com.myrunningapp.data.location.RunTracker] reports a coached run's
 * progress against its workout, so the plan can tick the workout off.
 *
 * The link is written when the run row is created, not only at the end, so a
 * run cut short by a process death can still be credited to its workout:
 * [com.myrunningapp.data.db.AppDatabase.RECOVER_INTERRUPTED_RUNS] settles it the
 * next time the database opens.
 *
 * An interface so the tracker's tests need no database, and so a run with no
 * plan behind it costs nothing.
 */
interface WorkoutLog {
    /** The run row for a coached run now exists; remember which run is doing the workout. */
    suspend fun startWorkoutRun(workoutId: Long, runId: Long)

    /** The run finished and covered enough to have done the workout. */
    suspend fun recordWorkoutRun(workoutId: Long, runId: Long)

    /** The run finished too short to count; the workout is still to do. */
    suspend fun abandonWorkoutRun(workoutId: Long, runId: Long)

    object None : WorkoutLog {
        override suspend fun startWorkoutRun(workoutId: Long, runId: Long) = Unit
        override suspend fun recordWorkoutRun(workoutId: Long, runId: Long) = Unit
        override suspend fun abandonWorkoutRun(workoutId: Long, runId: Long) = Unit
    }
}
