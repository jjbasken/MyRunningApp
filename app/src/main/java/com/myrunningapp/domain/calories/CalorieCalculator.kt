package com.myrunningapp.domain.calories

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.Profile
import com.myrunningapp.domain.model.Sex
import kotlin.math.roundToInt

/**
 * Estimates the energy cost of a run, walk or ride.
 *
 * Deliberately approximate — the same order of accuracy Endomondo and MapMyRun
 * offer without a heart-rate strap. Two standard pieces are stacked:
 *
 *  - The **ACSM metabolic equations** give oxygen uptake from speed on the flat:
 *    `0.2 × m/min` for running, `0.1 × m/min` for walking, plus a resting term.
 *    (Grade is ignored; GPS altitude is far too noisy to grade a route with.)
 *  - **Mifflin–St Jeor** supplies that resting term from the user's height, age
 *    and sex, instead of the one-size-fits-all 3.5 mL/kg/min the plain MET
 *    formula assumes. That is the only thing those three fields do here.
 *
 * Energy then follows from uptake directly: `kcal/min = VO₂ × kg / 200`, which is
 * the familiar `MET × 3.5 × kg / 200` with `MET × 3.5` written back out as VO₂.
 *
 * Pure functions — no Android, no clock, no I/O — so the numbers are pinned down
 * by unit tests rather than by going for a run.
 */
object CalorieCalculator {

    /** Oxygen cost of covering a metre, in mL/kg — the ACSM speed coefficients. */
    private const val RUNNING_VO2_PER_METER_PER_MINUTE = 0.2
    private const val WALKING_VO2_PER_METER_PER_MINUTE = 0.1

    /**
     * The same shape for cycling, which the ACSM has no outdoor equation for —
     * its cycling formula is for a leg ergometer's known power output, and a
     * phone knows only how far the bike went.
     *
     * So this is a least-squares fit of the same `coefficient × m/min + resting`
     * form to the Compendium of Physical Activities' outdoor-cycling entries
     * (6.8 METs at 10–12 mph rising to 12 METs at 16–19 mph), which holds to
     * within about 10% from 10 to 18 mph — the range nearly every ride sits in.
     * Below running and walking both, as it should be: a bike carries its rider
     * further per millilitre of oxygen than legs do.
     */
    private const val CYCLING_VO2_PER_METER_PER_MINUTE = 0.075

    /** 1 L of oxygen releases roughly 5 kcal; `200 = 1000 mL / 5 kcal`. */
    private const val ML_OXYGEN_PER_KCAL = 200.0

    /** kcal/day to mL O₂/kg/min: `1440 min/day × 5 kcal/L ÷ 1000 mL/L`. */
    private const val KCAL_PER_DAY_TO_VO2 = 7.2

    /**
     * Bounds on the resting term. Mifflin–St Jeor is fitted to ordinary adults;
     * a profile far outside its range should not be allowed to distort a run's
     * whole estimate, so it is clamped either side of the textbook 3.5.
     */
    private val RESTING_VO2_RANGE = 2.0..5.0

    /**
     * The user's resting oxygen uptake in mL/kg/min — their personal stand-in for
     * the MET formula's flat 3.5.
     */
    fun restingVo2(profile: Profile): Double {
        if (profile.weightKg <= 0.0) return 3.5
        val sexOffset = if (profile.sex == Sex.MALE) 5.0 else -161.0
        val basalKcalPerDay =
            10.0 * profile.weightKg + 6.25 * profile.heightCm - 5.0 * profile.age + sexOffset
        val vo2 = basalKcalPerDay / (KCAL_PER_DAY_TO_VO2 * profile.weightKg)
        return vo2.coerceIn(RESTING_VO2_RANGE.start, RESTING_VO2_RANGE.endInclusive)
    }

    /**
     * Calories burned over [movingDurationSec] of moving, covering
     * [distanceMeters].
     *
     * Pass the run's **weight snapshot** in [profile] rather than today's weight,
     * so re-estimating an old run does not quietly rewrite it against a body the
     * user no longer had.
     *
     * Returns 0 rather than a guess for input that cannot describe an activity —
     * a run with no time, negative distance, or a profile with no weight.
     */
    fun calories(
        activityType: ActivityType,
        distanceMeters: Double,
        movingDurationSec: Long,
        profile: Profile,
    ): Int {
        if (movingDurationSec <= 0L || distanceMeters < 0.0 || profile.weightKg <= 0.0) return 0
        if (!distanceMeters.isFinite()) return 0

        val minutes = movingDurationSec / 60.0
        val metersPerMinute = distanceMeters / minutes
        val speedCoefficient = when (activityType) {
            ActivityType.RUN -> RUNNING_VO2_PER_METER_PER_MINUTE
            ActivityType.WALK -> WALKING_VO2_PER_METER_PER_MINUTE
            ActivityType.BIKE -> CYCLING_VO2_PER_METER_PER_MINUTE
        }
        val vo2 = speedCoefficient * metersPerMinute + restingVo2(profile)
        val kcalPerMinute = vo2 * profile.weightKg / ML_OXYGEN_PER_KCAL
        return (kcalPerMinute * minutes).roundToInt().coerceAtLeast(0)
    }
}
