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
     * @param now when the fix is being handled, used for the staleness check.
     */
    fun evaluate(fix: GpsFix, previous: GpsFix?, now: Instant): FixVerdict {
        if (fix.accuracyMeters > maxAccuracyMeters) return FixVerdict.POOR_ACCURACY
        if (Duration.between(fix.timestamp, now) > maxAge) return FixVerdict.STALE

        if (previous != null) {
            val elapsedSeconds =
                Duration.between(previous.timestamp, fix.timestamp).toMillis() / 1000.0
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
