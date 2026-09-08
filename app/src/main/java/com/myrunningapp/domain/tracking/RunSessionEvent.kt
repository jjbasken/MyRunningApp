package com.myrunningapp.domain.tracking

/** A GPS fix the session kept, tagged with the segment it belongs to. */
data class TrackedPoint(
    val fix: GpsFix,
    val segmentIndex: Int,
)

/**
 * One completed mile (or the shorter final stretch), as the session sees it —
 * no database identifiers yet. The repository turns this into a
 * [com.myrunningapp.domain.model.Split] row when it persists it.
 */
data class MileSplit(
    val splitNumber: Int,
    val distanceMeters: Double,
    val durationSec: Long,
    val paceSecPerMile: Double,
    /**
     * Moving time from the start of the run to the end of this split.
     *
     * Carried alongside the split's own duration because the mile announcement
     * quotes both, and summing the rounded per-split durations would let the
     * spoken total drift a second or two away from the clock on screen.
     */
    val cumulativeMovingSec: Long,
)

/**
 * Everything a [RunSession] wants to tell the outside world. The session itself
 * neither writes to the database nor speaks: it reports, and the service acts.
 */
sealed interface RunSessionEvent {

    /** Keep this point — it belongs on the map and in `run_points`. */
    data class PointRecorded(val point: TrackedPoint) : RunSessionEvent

    /** A fix failed the filter. Useful for a "poor GPS" hint in the UI. */
    data class FixRejected(val verdict: FixVerdict) : RunSessionEvent

    /** A mile marker was crossed — persist the split and announce it. */
    data class MileCompleted(val split: MileSplit) : RunSessionEvent

    /**
     * The leftover distance since the last mile marker, closed off at finish.
     * Kept separate from [MileCompleted] because it is a partial mile: it belongs
     * in the splits table but must never trigger a mile announcement.
     */
    data class FinalSplitCompleted(val split: MileSplit) : RunSessionEvent

    /** The countdown reached zero and distance is now accumulating. */
    data object TrackingStarted : RunSessionEvent

    /** The countdown moved to a new whole second (for the on-screen ticker and voice). */
    data class CountdownTick(val secondsRemaining: Int) : RunSessionEvent
}
