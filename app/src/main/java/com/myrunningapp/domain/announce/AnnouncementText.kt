package com.myrunningapp.domain.announce

import com.myrunningapp.domain.Units
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
    }

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
