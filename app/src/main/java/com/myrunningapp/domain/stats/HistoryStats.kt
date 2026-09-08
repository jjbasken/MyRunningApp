package com.myrunningapp.domain.stats

import com.myrunningapp.domain.Units
import com.myrunningapp.domain.model.Run
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The summed-up view of a set of runs, as drawn in the history header.
 */
data class RunTotals(
    val runCount: Int,
    val distanceMeters: Double,
    val movingDurationSec: Long,
    val calories: Int,
) {
    /**
     * Pace across the whole set — total time over total distance, not the mean of
     * the per-run paces, which would weight a warm-up mile like a long run.
     * NaN when nothing has been covered.
     */
    val avgPaceSecPerMile: Double
        get() = Units.paceSecPerMile(distanceMeters, movingDurationSec)

    companion object {
        val EMPTY = RunTotals(0, 0.0, 0L, 0)
    }
}

/**
 * Rolls a run list up into the totals the history screen shows. Pure functions
 * over an explicit clock and zone, so "this week" is testable without waiting
 * for one.
 */
object HistoryStats {

    fun totals(runs: List<Run>): RunTotals {
        if (runs.isEmpty()) return RunTotals.EMPTY
        return RunTotals(
            runCount = runs.size,
            distanceMeters = runs.sumOf { it.distanceMeters },
            movingDurationSec = runs.sumOf { it.movingDurationSec },
            calories = runs.sumOf { it.calories },
        )
    }

    /**
     * Local midnight on the most recent [firstDayOfWeek] — today, if today is it.
     *
     * The caller supplies the first day rather than this assuming one: a week
     * starts on Sunday for the user and on Monday for most of the world.
     */
    fun startOfWeek(now: Instant, zone: ZoneId, firstDayOfWeek: DayOfWeek): Instant =
        now.atZone(zone)
            .with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()

    /**
     * Totals for the current week. Runs are matched on when they *started*, so a
     * run that crosses midnight into the new week belongs to the week it began in.
     * A start time in the future still counts — a device clock a few seconds fast
     * should not make a run the user just finished disappear from the header.
     */
    fun weekTotals(
        runs: List<Run>,
        now: Instant,
        zone: ZoneId,
        firstDayOfWeek: DayOfWeek,
    ): RunTotals {
        val start = startOfWeek(now, zone, firstDayOfWeek)
        return totals(runs.filter { !it.startedAt.isBefore(start) })
    }
}
