package com.myrunningapp.domain.announce

import com.myrunningapp.domain.model.ActivityType

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
}
