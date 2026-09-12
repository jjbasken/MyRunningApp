package com.myrunningapp.domain

import com.myrunningapp.domain.model.ActivityType
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import java.util.Locale

/**
 * Unit conversions and display formatting. The app stores everything in metric
 * and SI (metres, seconds, kilograms) and converts here, at the UI boundary,
 * to the miles/feet/pounds the user works in.
 */
object Units {

    const val METERS_PER_MILE = 1609.344
    private const val SECONDS_PER_HOUR = 3600.0
    private const val KG_PER_LB = 0.45359237
    private const val CM_PER_INCH = 2.54

    // --- distance -------------------------------------------------------------

    fun metersToMiles(meters: Double): Double = meters / METERS_PER_MILE

    fun milesToMeters(miles: Double): Double = miles * METERS_PER_MILE

    /** e.g. 5346.0 -> "3.32 mi" */
    fun formatMiles(meters: Double): String =
        String.format("%.2f mi", metersToMiles(meters))

    // --- weight / height -----------------------------------------------------

    fun lbToKg(lb: Double): Double = lb * KG_PER_LB

    fun kgToLb(kg: Double): Double = kg / KG_PER_LB

    fun inchesToCm(inches: Double): Double = inches * CM_PER_INCH

    fun cmToInches(cm: Double): Double = cm / CM_PER_INCH

    // --- time / pace -------------------------------------------------------

    /** 3725 -> "1:02:05", 185 -> "3:05". */
    fun formatDuration(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        val seconds = s % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    /** Pace in seconds per mile -> "9:05 /mi". Blank for non-finite / zero. */
    fun formatPace(secPerMile: Double): String {
        if (!secPerMile.isFinite() || secPerMile <= 0.0) return "--:-- /mi"
        val total = secPerMile.roundToLong()
        val minutes = total / 60
        val seconds = total % 60
        return String.format("%d:%02d /mi", minutes, seconds)
    }

    /**
     * Pace in seconds per mile -> "14.2 mph". Blank for non-finite / zero.
     *
     * The app stores every activity as a pace, because that is what a run is;
     * a ride is the same number read the other way up.
     */
    fun formatSpeedMph(secPerMile: Double): String {
        if (!secPerMile.isFinite() || secPerMile <= 0.0) return "-- mph"
        return String.format(Locale.US, "%.1f mph", SECONDS_PER_HOUR / secPerMile)
    }

    /** Whichever of [formatPace] and [formatSpeedMph] [activityType] is read in. */
    fun formatPaceOrSpeed(secPerMile: Double, activityType: ActivityType): String =
        if (activityType.readsAsSpeed) formatSpeedMph(secPerMile) else formatPace(secPerMile)

    fun paceSecPerMile(distanceMeters: Double, durationSec: Long): Double {
        val miles = metersToMiles(distanceMeters)
        return if (miles <= 0.0) Double.NaN else durationSec / miles
    }

    fun roundLb(kg: Double): Int = kgToLb(kg).roundToInt()

    fun roundInches(cm: Double): Int = cmToInches(cm).roundToInt()
}
