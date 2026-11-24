package com.myrunningapp.domain.plan

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * One piece of between-runs coaching on the plan screen.
 *
 * A description, not a sentence, so the wording stays in string resources and
 * the rules that pick a tip stay in [PlanOverview.of], under test.
 */
sealed interface CoachTip {
    /** The plan's first workout is still ahead. */
    data class NotStarted(val daysUntilStart: Long) : CoachTip

    /** Race in under a week. */
    data class RaceWeek(val daysToRace: Long) : CoachTip

    /** The two weeks before race week: the taper. */
    data class Taper(val daysToRace: Long) : CoachTip

    /** Runs left undone in the last seven days. */
    data class MissedRuns(val count: Int) : CoachTip

    /** Recent easy runs ran well ahead of their target pace. */
    data class EasyRunsTooFast(val count: Int) : CoachTip

    data object LongRunToday : CoachTip

    data object RestDay : CoachTip

    /** Nothing to correct: how much of what was due has been done. */
    data class OnTrack(val completed: Int, val due: Int) : CoachTip

    /** The race date has passed. */
    data object PlanFinished : CoachTip
}

/** What the plan screen and the track screen need to know about where the runner is. */
data class PlanOverview(
    /** Today's run, if the plan has one still to do — what the tracker coaches. */
    val todaysRun: PlannedWorkout?,
    /** Anything on today's date, including a rest or cross-training day. */
    val today: List<PlannedWorkout>,
    /** The next run still to do after today. */
    val nextRun: PlannedWorkout?,
    val daysToRace: Long?,
    /** Runs completed out of every run in the plan. */
    val completedRuns: Int,
    val totalRuns: Int,
    val weekPlannedMeters: Double,
    val weekCompletedMeters: Double,
    val tips: List<CoachTip>,
) {
    companion object {
        private const val EASY_TOO_FAST_MARGIN_SEC = 20.0
        private const val RECENT_EASY_RUNS = 4

        /** Runs that come back as "today's run" for the tracker. */
        fun todaysRun(workouts: List<PlannedWorkout>, today: LocalDate): PlannedWorkout? =
            workouts.firstOrNull { it.date == today && WorkoutCoach.coaches(it) }

        fun of(plan: TrainingPlan, workouts: List<PlannedWorkout>, today: LocalDate): PlanOverview {
            val runs = workouts.filter { it.type.isRun }
            val todays = workouts.filter { it.date == today }
            val daysToRace = plan.raceDate?.let { ChronoUnit.DAYS.between(today, it) }

            val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weekEnd = weekStart.plusDays(6)
            val thisWeek = runs.filter { it.date in weekStart..weekEnd }

            return PlanOverview(
                todaysRun = todaysRun(workouts, today),
                today = todays,
                nextRun = runs.filter { it.date > today && it.status == WorkoutStatus.PLANNED }
                    .minByOrNull { it.date },
                daysToRace = daysToRace,
                completedRuns = runs.count { it.status == WorkoutStatus.COMPLETED },
                totalRuns = runs.size,
                weekPlannedMeters = thisWeek.sumOf { it.distanceMeters ?: 0.0 },
                weekCompletedMeters = thisWeek
                    .filter { it.status == WorkoutStatus.COMPLETED }
                    .sumOf { it.completedDistanceMeters ?: it.distanceMeters ?: 0.0 },
                tips = tips(workouts, runs, todays, today, daysToRace),
            )
        }

        /**
         * At most a couple of tips, most important first. The order is the
         * coaching priority: the race outranks everything, and a pattern of missed
         * runs outranks a pace correction.
         */
        private fun tips(
            workouts: List<PlannedWorkout>,
            runs: List<PlannedWorkout>,
            todays: List<PlannedWorkout>,
            today: LocalDate,
            daysToRace: Long?,
        ): List<CoachTip> {
            if (daysToRace != null && daysToRace < 0) return listOf(CoachTip.PlanFinished)
            val first = workouts.minOfOrNull { it.date } ?: return emptyList()
            if (first > today) {
                return listOf(CoachTip.NotStarted(ChronoUnit.DAYS.between(today, first)))
            }

            val tips = mutableListOf<CoachTip>()
            when {
                daysToRace == null -> Unit
                daysToRace <= 6 -> tips += CoachTip.RaceWeek(daysToRace)
                daysToRace <= 20 -> tips += CoachTip.Taper(daysToRace)
            }

            val missed = runs.count {
                it.status == WorkoutStatus.PLANNED && it.date < today && it.date >= today.minusDays(7)
            }
            if (missed > 0) tips += CoachTip.MissedRuns(missed)

            val recentEasy = runs
                .filter {
                    (it.type == WorkoutType.EASY || it.type == WorkoutType.RECOVERY) &&
                        it.status == WorkoutStatus.COMPLETED && it.date <= today &&
                        it.paceFastSecPerMile != null && it.completedPaceSecPerMile != null
                }
                .sortedByDescending { it.date }
                .take(RECENT_EASY_RUNS)
            val tooFast = recentEasy.count {
                val pace = checkNotNull(it.completedPaceSecPerMile)
                pace > 0.0 && pace < checkNotNull(it.paceFastSecPerMile) - EASY_TOO_FAST_MARGIN_SEC
            }
            if (tooFast >= 2) tips += CoachTip.EasyRunsTooFast(tooFast)

            when {
                todays.any { it.type == WorkoutType.LONG && it.status == WorkoutStatus.PLANNED } ->
                    tips += CoachTip.LongRunToday
                todays.none { it.type.isRun } -> tips += CoachTip.RestDay
            }

            val due = runs.filter { it.date < today && it.status != WorkoutStatus.SKIPPED }
            if (missed == 0 && due.isNotEmpty()) {
                tips += CoachTip.OnTrack(
                    completed = due.count { it.status == WorkoutStatus.COMPLETED },
                    due = due.size,
                )
            }
            return tips.take(3)
        }
    }
}
