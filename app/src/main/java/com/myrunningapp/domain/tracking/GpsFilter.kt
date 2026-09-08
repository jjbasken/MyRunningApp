package com.myrunningapp.domain.tracking

import java.time.Duration
import java.time.Instant

/** Why a fix was kept or thrown away. Named cases make the rules testable and loggable. */
enum class FixVerdict {
    ACCEPTED,

    /** The reported accuracy radius is too wide to trust. */
    POOR_ACCURACY,

    /** The fix arrived too long after it was taken to still describe where we are. */
    STALE,

    /** The fix predates the current unpaused tracking window. */
    BEFORE_TRACKING,

    /** A fix cannot have been taken after the time it is delivered. */
    FUTURE,

    /** Its timestamp is not after the previous accepted fix, so it adds nothing. */
    OUT_OF_ORDER,

    /** Reaching it from the previous fix would need a speed no runner can hold. */
    IMPLAUSIBLE_SPEED,
}

/**
 * Decides which GPS fixes are allowed to move the distance total.
 *
 * The three rules are deliberately blunt — GPS noise on a phone shows up as wide
 * accuracy radii and as sudden teleports, and both would otherwise inflate a run's
 * distance. Rejecting a fix costs nothing: the next one arrives a second later.
 */
class GpsFilter(
    private val maxAccuracyMeters: Float = 25f,
    private val maxSpeedMetersPerSecond: Double = 13.0,
    private val maxAge: Duration = Duration.ofSeconds(5),
) {

    /**
     * @param previous the last fix that was accepted, or null if this is the first.
     * @param now wall time for dated replays. Android callers supply elapsedRealtimeMillis.
     * @param elapsedRealtimeMillis time since boot when this fix is handled.
     */
    fun evaluate(
        fix: GpsFix,
        previous: GpsFix?,
        now: Instant,
        elapsedRealtimeMillis: Long = now.toEpochMilli(),
    ): FixVerdict {
        if (fix.accuracyMeters > maxAccuracyMeters) return FixVerdict.POOR_ACCURACY
        val ageMillis = elapsedRealtimeMillis - fix.elapsedRealtimeMillis
        if (ageMillis < 0) return FixVerdict.FUTURE
        if (ageMillis > maxAge.toMillis()) return FixVerdict.STALE

        if (previous != null) {
            val elapsedSeconds =
                (fix.elapsedRealtimeMillis - previous.elapsedRealtimeMillis) / 1000.0
            if (elapsedSeconds <= 0.0) return FixVerdict.OUT_OF_ORDER

            val meters = Geo.distanceMeters(
                previous.latitude, previous.longitude, fix.latitude, fix.longitude,
            )
            if (meters / elapsedSeconds > maxSpeedMetersPerSecond) {
                return FixVerdict.IMPLAUSIBLE_SPEED
            }
        }
        return FixVerdict.ACCEPTED
    }
}
