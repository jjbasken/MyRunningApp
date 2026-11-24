package com.myrunningapp.domain.announce

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.plan.WorkoutType

/**
 * Something the app wants to say out loud during a run.
 *
 * Deliberately a description of the *event*, not a string: the wording lives in
 * [AnnouncementText], a pure function, so every line the app speaks is unit-tested
 * without a `TextToSpeech` anywhere near the test.
 */
sealed interface Announcement {

    /**
     * How urgent the line is, which decides what happens when one announcement
     * arrives while another is still being spoken.
     */
    val priority: Priority
        get() = Priority.NORMAL

    enum class Priority {
        /** Queue behind whatever is speaking. */
        NORMAL,

        /** Say it now — a countdown cue is worthless a second late. */
        IMMEDIATE,
    }

    /** One of the last few seconds before tracking begins: "3", "2", "1". */
    data class CountdownCue(val secondsRemaining: Int) : Announcement {
        override val priority = Priority.IMMEDIATE
    }

    /** Distance is now accumulating — the "go" at the end of the countdown. */
    data object Started : Announcement {
        override val priority = Priority.IMMEDIATE
    }

    data object Paused : Announcement

    data object Resumed : Announcement

    /**
     * A mile marker was crossed. The design's headline line: cumulative distance,
     * total time, and the pace of the mile just finished.
     *
     * @param mileNumber 1 for the first mile.
     * @param totalMovingSec moving time at the moment of the crossing.
     * @param lastMilePaceSec how long that mile took — for a full mile, its pace.
     * @param activityType decides whether that is read out as a pace or a speed.
     */
    data class MileCompleted(
        val mileNumber: Int,
        val totalMovingSec: Long,
        val lastMilePaceSec: Double,
        val activityType: ActivityType = ActivityType.RUN,
    ) : Announcement

    /** The wrap-up spoken once the run is over. */
    data class Finished(
        val distanceMeters: Double,
        val movingDurationSec: Long,
        val avgPaceSecPerMile: Double,
        val activityType: ActivityType = ActivityType.RUN,
    ) : Announcement

    // --- training-plan coaching ------------------------------------------------
    // Spoken only when a run was started against a planned workout; see
    // com.myrunningapp.domain.plan.WorkoutCoach for when each one is said.

    /** Straight after "Go": what today's session is. */
    data class WorkoutIntro(
        val title: String,
        val distanceMeters: Double?,
        val durationSec: Long?,
        val paceFastSecPerMile: Double?,
        val paceSlowSecPerMile: Double?,
    ) : Announcement

    /** How the mile just finished compared with the plan's target pace. */
    enum class PaceVerdict { ON_TARGET, TOO_FAST, TOO_SLOW }

    /**
     * Follows a mile line: a pace verdict, how much of the workout is left, or
     * both. Either may be null, never both.
     *
     * @param secondsOff how far outside the target range the mile was, rounded.
     */
    data class WorkoutCheckIn(
        val workoutType: WorkoutType,
        val verdict: PaceVerdict?,
        val secondsOff: Long,
        val remainingMeters: Double?,
        val remainingSec: Long?,
    ) : Announcement

    /** Halfway through a workout set by time, where there are no miles to count down. */
    data class WorkoutHalfway(val remainingSec: Long) : Announcement

    /** The planned distance or time is done; the run is still going until Finish. */
    data class WorkoutTargetReached(
        val distanceMeters: Double?,
        val durationSec: Long?,
    ) : Announcement
}
