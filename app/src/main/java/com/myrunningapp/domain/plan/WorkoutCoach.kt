package com.myrunningapp.domain.plan

import com.myrunningapp.domain.Units
import com.myrunningapp.domain.announce.Announcement
import com.myrunningapp.domain.model.RunSessionState
import com.myrunningapp.domain.tracking.MileSplit
import com.myrunningapp.domain.tracking.RunSnapshot
import kotlin.math.roundToLong

/**
 * Coaches one run against one planned workout.
 *
 * Decides *when* the coach speaks; [com.myrunningapp.domain.announce.AnnouncementText]
 * decides the words. Pure and stateful only in what it has already said, so a
 * whole run's worth of coaching can be checked by feeding it splits and snapshots.
 *
 * The rules:
 *  - **Start:** what the session is, its target, its pace.
 *  - **Each mile:** how that mile compared with the target pace, and how much is
 *    left. Interval and tempo sessions get no pace verdict: their mile averages mix
 *    the hard reps with the recoveries and warm-up, so any verdict would be wrong.
 *    An easy or recovery run that is slow gets no verdict either — slow is the
 *    point of those.
 *  - **Halfway:** only for a workout set by time, which has no miles to count down.
 *  - **Target reached:** once, the moment the planned distance (or, failing that,
 *    time) is done. The run keeps recording until the runner presses Finish.
 */
class WorkoutCoach(val workout: PlannedWorkout) {

    private var halfwaySaid = false
    private var targetReachedSaid = false

    private val targetDistance: Double? = workout.distanceMeters
    /** Time counts only when the plan gave no distance; a distance wins when both are set. */
    private val targetDuration: Long? = workout.durationSec.takeIf { targetDistance == null }

    fun intro(): Announcement = Announcement.WorkoutIntro(
        title = workout.title,
        distanceMeters = workout.distanceMeters,
        durationSec = workout.durationSec,
        paceFastSecPerMile = workout.paceFastSecPerMile.takeIf { pacesAreCoached },
        paceSlowSecPerMile = workout.paceSlowSecPerMile.takeIf { pacesAreCoached },
    )

    /** Said straight after the mile line, or null when there is nothing to add. */
    fun onMile(split: MileSplit): Announcement? {
        if (targetReachedSaid) return null

        val (verdict, secondsOff) = verdict(split.paceSecPerMile)
        val coveredMeters = split.splitNumber * Units.METERS_PER_MILE
        val remainingMeters = targetDistance?.let { it - coveredMeters }
            ?.takeIf { it >= MIN_REMAINING_METERS }
        val remainingSec = targetDuration?.let { it - split.cumulativeMovingSec }
            ?.takeIf { it >= MIN_REMAINING_SEC }

        if (verdict == null && remainingMeters == null && remainingSec == null) return null
        return Announcement.WorkoutCheckIn(
            workoutType = workout.type,
            verdict = verdict,
            secondsOff = secondsOff,
            remainingMeters = remainingMeters,
            remainingSec = remainingSec,
        )
    }

    /** Called with every snapshot while tracking; speaks at most once per milestone. */
    fun onProgress(snapshot: RunSnapshot): Announcement? {
        if (snapshot.state != RunSessionState.TRACKING || targetReachedSaid) return null

        val reached = when {
            targetDistance != null -> snapshot.distanceMeters >= targetDistance
            targetDuration != null -> snapshot.movingDurationSec >= targetDuration
            else -> false
        }
        if (reached) {
            targetReachedSaid = true
            return Announcement.WorkoutTargetReached(
                distanceMeters = targetDistance,
                durationSec = targetDuration,
            )
        }

        if (targetDuration != null && targetDuration >= MIN_DURATION_FOR_HALFWAY && !halfwaySaid &&
            snapshot.movingDurationSec * 2 >= targetDuration
        ) {
            halfwaySaid = true
            return Announcement.WorkoutHalfway(targetDuration - snapshot.movingDurationSec)
        }
        return null
    }

    private val pacesAreCoached: Boolean
        get() = workout.hasPaceTarget &&
            workout.type != WorkoutType.INTERVALS && workout.type != WorkoutType.TEMPO

    private fun verdict(paceSecPerMile: Double): Pair<Announcement.PaceVerdict?, Long> {
        if (!pacesAreCoached || !paceSecPerMile.isFinite() || paceSecPerMile <= 0.0) return null to 0L
        val fast = checkNotNull(workout.paceFastSecPerMile)
        val slow = checkNotNull(workout.paceSlowSecPerMile)
        // A single target pace gets a wider band than a range: nobody runs an exact pace.
        val tolerance = if (slow - fast < 1.0) SINGLE_PACE_TOLERANCE_SEC else RANGE_TOLERANCE_SEC
        return when {
            paceSecPerMile < fast - tolerance ->
                Announcement.PaceVerdict.TOO_FAST to (fast - paceSecPerMile).roundToLong()
            paceSecPerMile > slow + tolerance -> {
                val easyDay = workout.type == WorkoutType.EASY || workout.type == WorkoutType.RECOVERY
                if (easyDay) null to 0L
                else Announcement.PaceVerdict.TOO_SLOW to (paceSecPerMile - slow).roundToLong()
            }
            else -> Announcement.PaceVerdict.ON_TARGET to 0L
        }
    }

    companion object {
        /** Whether a run of [workout] gets a coach at all. */
        fun coaches(workout: PlannedWorkout): Boolean =
            workout.type.isRun && workout.status == WorkoutStatus.PLANNED

        private const val RANGE_TOLERANCE_SEC = 5.0
        private const val SINGLE_PACE_TOLERANCE_SEC = 10.0
        /** About a tenth of a mile: less than that left and "to go" is noise before "complete". */
        private const val MIN_REMAINING_METERS = 150.0
        private const val MIN_REMAINING_SEC = 60L
        private const val MIN_DURATION_FOR_HALFWAY = 20 * 60L
    }
}
