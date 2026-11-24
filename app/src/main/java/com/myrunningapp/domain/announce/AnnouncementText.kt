package com.myrunningapp.domain.announce

import com.myrunningapp.domain.Units
import com.myrunningapp.domain.plan.WorkoutType
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Turns an [Announcement] into the sentence the app speaks.
 *
 * A pure function of the event, with no Android and no state, so the exact wording
 * — including the awkward cases: one mile rather than "1 miles", a round nine
 * minutes rather than "9 minutes 0 seconds" — is pinned down by unit tests instead
 * of by listening to a phone.
 *
 * Written for the ear, not the eye: numbers are spelled the way a speech engine
 * reads them naturally, and each sentence ends in a full stop so the engine pauses.
 */
object AnnouncementText {

    private const val SECONDS_PER_HOUR = 3600.0

    fun of(announcement: Announcement): String = when (announcement) {
        is Announcement.CountdownCue -> "${announcement.secondsRemaining}."
        Announcement.Started -> "Go."
        Announcement.Paused -> "Paused."
        Announcement.Resumed -> "Resumed."
        is Announcement.MileCompleted -> mileLine(announcement)
        is Announcement.Finished -> finishLine(announcement)
        is Announcement.WorkoutIntro -> workoutIntro(announcement)
        is Announcement.WorkoutCheckIn -> checkIn(announcement)
        is Announcement.WorkoutHalfway ->
            "Halfway. ${spokenDuration(roundUpToMinute(announcement.remainingSec))} to go."
        is Announcement.WorkoutTargetReached -> targetReached(announcement)
    }

    // --- coaching ------------------------------------------------------------

    /**
     * "Today's workout: Long run. 6 miles. Target pace, 11 minutes to 11 minutes
     * 30 seconds per mile."
     */
    private fun workoutIntro(event: Announcement.WorkoutIntro): String = buildString {
        append("Today's workout: ")
        append(event.title.trimEnd('.'))
        append(".")
        val target = when {
            event.distanceMeters != null -> decimalMiles(event.distanceMeters)
            event.durationSec != null -> spokenDuration(event.durationSec)
            else -> null
        }
        target?.let { append(" ").append(it).append(".") }
        val fast = event.paceFastSecPerMile
        val slow = event.paceSlowSecPerMile
        if (fast != null && slow != null) {
            append(" Target pace, ")
            append(spokenDuration(fast.roundToLong()))
            if (slow.roundToLong() != fast.roundToLong()) {
                append(" to ")
                append(spokenDuration(slow.roundToLong()))
            }
            append(" per mile.")
        }
    }

    /**
     * "20 seconds faster than target. Ease off a little. 2 miles to go."
     *
     * Slow is read kindly except on race day: on a long run, finishing the
     * distance matters more than the pace, and nagging someone who is already
     * tired is how runs get abandoned.
     */
    private fun checkIn(event: Announcement.WorkoutCheckIn): String = buildList {
        when (event.verdict) {
            Announcement.PaceVerdict.ON_TARGET -> add("Right on target pace.")
            Announcement.PaceVerdict.TOO_FAST -> add(
                "${plural(event.secondsOff, "second")} faster than target. " +
                    if (event.workoutType == WorkoutType.RACE) "Save some for later." else "Ease off a little.",
            )
            Announcement.PaceVerdict.TOO_SLOW -> add(
                "${plural(event.secondsOff, "second")} slower than target. " +
                    if (event.workoutType == WorkoutType.RACE) "Pick it up if you can." else "That's okay, keep it steady.",
            )
            null -> Unit
        }
        event.remainingMeters?.let { add("${decimalMiles(it)} to go.") }
            ?: event.remainingSec?.let { add("${spokenDuration(roundUpToMinute(it))} to go.") }
    }.joinToString(" ")

    /** "Workout complete. 6 miles done. Great work — cool down, then press finish." */
    private fun targetReached(event: Announcement.WorkoutTargetReached): String = buildString {
        append("Workout complete. ")
        when {
            event.distanceMeters != null -> append(decimalMiles(event.distanceMeters)).append(" done. ")
            event.durationSec != null -> append(spokenDuration(event.durationSec)).append(" done. ")
        }
        append("Great work. Cool down, then press finish.")
    }

    /** Time left is read in whole minutes: "12 minutes to go", never "11 minutes 37 seconds". */
    private fun roundUpToMinute(seconds: Long): Long =
        if (seconds < 60) seconds.coerceAtLeast(0) else ((seconds + 59) / 60) * 60

    /**
     * "3 miles. Time, 27 minutes 42 seconds. Last mile pace, 9 minutes 5 seconds."
     *
     * On a bike the same mile is read the other way up — "Last mile speed, 15.0
     * miles per hour" — because that is the number a cyclist is riding to.
     */
    private fun mileLine(event: Announcement.MileCompleted): String = buildString {
        append(wholeMiles(event.mileNumber))
        append(". Time, ")
        append(spokenDuration(event.totalMovingSec))
        if (event.activityType.readsAsSpeed) {
            append(". Last mile speed, ")
            append(spokenSpeed(event.lastMilePaceSec))
        } else {
            append(". Last mile pace, ")
            append(spokenDuration(event.lastMilePaceSec.roundToLong()))
        }
        append(".")
    }

    /**
     * "Run complete. Total distance 3.4 miles, time 31 minutes 12 seconds,
     * average pace 9 minutes 5 seconds per mile." A ride says "Ride complete"
     * and closes on an average speed instead.
     */
    private fun finishLine(event: Announcement.Finished): String = buildString {
        val ride = event.activityType.readsAsSpeed
        append(if (ride) "Ride complete. Total distance " else "Run complete. Total distance ")
        append(decimalMiles(event.distanceMeters))
        append(", time ")
        append(spokenDuration(event.movingDurationSec))
        val pace = event.avgPaceSecPerMile
        if (pace.isFinite() && pace > 0.0) {
            if (ride) {
                append(", average speed ")
                append(spokenSpeed(pace))
            } else {
                append(", average pace ")
                append(spokenDuration(pace.roundToLong()))
                append(" per mile")
            }
        }
        append(".")
    }

    /**
     * A pace in seconds per mile as a speech engine should read it in mph:
     * 240.0 -> "15.0 miles per hour". The tenth is kept even when round, so the
     * engine says "fifteen point oh" rather than a bare "fifteen" that could be
     * mistaken for the distance it follows.
     */
    private fun spokenSpeed(secPerMile: Double): String {
        val mph = SECONDS_PER_HOUR / secPerMile
        return String.format(Locale.US, "%.1f miles per hour", mph)
    }

    /** 1 -> "1 mile", 3 -> "3 miles". */
    private fun wholeMiles(miles: Int): String =
        if (miles == 1) "1 mile" else "$miles miles"

    /** 5471 m -> "3.4 miles"; a distance that rounds to one mile stays singular. */
    private fun decimalMiles(meters: Double): String {
        val miles = Units.metersToMiles(meters)
        val text = String.format(Locale.US, "%.2f", miles).trimEnd('0').trimEnd('.')
        return if (text == "1") "1 mile" else "$text miles"
    }

    /**
     * Seconds as a speech engine should read them: "9 minutes 5 seconds",
     * "1 hour 3 minutes", "45 seconds". Zero components are dropped so a round
     * nine-minute mile is not read as "9 minutes 0 seconds".
     */
    private fun spokenDuration(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val parts = buildList {
            val hours = s / 3600
            val minutes = (s % 3600) / 60
            val seconds = s % 60
            if (hours > 0) add(plural(hours, "hour"))
            if (minutes > 0) add(plural(minutes, "minute"))
            if (seconds > 0) add(plural(seconds, "second"))
        }
        return if (parts.isEmpty()) "0 seconds" else parts.joinToString(" ")
    }

    private fun plural(count: Long, unit: String): String =
        if (count == 1L) "1 $unit" else "$count ${unit}s"
}
