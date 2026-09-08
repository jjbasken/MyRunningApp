package com.myrunningapp.domain.stats

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.Run
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class HistoryStatsTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")

    /** Thursday, 3 September 2026, 10:00 local. */
    private val now: Instant =
        ZonedDateTime.of(2026, 9, 3, 10, 0, 0, 0, zone).toInstant()

    private fun run(
        id: Long,
        at: ZonedDateTime,
        distanceMeters: Double = 1609.344,
        movingDurationSec: Long = 600,
        calories: Int = 100,
    ) = Run(
        id = id,
        startedAt = at.toInstant(),
        endedAt = at.toInstant().plusSeconds(movingDurationSec),
        activityType = ActivityType.RUN,
        distanceMeters = distanceMeters,
        movingDurationSec = movingDurationSec,
        elapsedDurationSec = movingDurationSec,
        avgPaceSecPerMile = 600.0,
        calories = calories,
        weightKgAtRun = 70.0,
    )

    private fun sep(day: Int, hour: Int) = ZonedDateTime.of(2026, 9, day, hour, 0, 0, 0, zone)

    private fun aug(day: Int, hour: Int) = ZonedDateTime.of(2026, 8, day, hour, 0, 0, 0, zone)

    @Test
    fun `totals of no runs are all zero`() {
        val totals = HistoryStats.totals(emptyList())
        assertEquals(0, totals.runCount)
        assertEquals(0.0, totals.distanceMeters, 0.0)
        assertEquals(0L, totals.movingDurationSec)
        assertEquals(0, totals.calories)
    }

    @Test
    fun `totals add up distance time and calories`() {
        val totals = HistoryStats.totals(
            listOf(
                run(1, sep(1, 8), distanceMeters = 5000.0, movingDurationSec = 1500, calories = 380),
                run(2, sep(2, 8), distanceMeters = 3000.0, movingDurationSec = 900, calories = 220),
            ),
        )
        assertEquals(2, totals.runCount)
        assertEquals(8000.0, totals.distanceMeters, 1e-9)
        assertEquals(2400L, totals.movingDurationSec)
        assertEquals(600, totals.calories)
    }

    @Test
    fun `a week starting Sunday begins on the Sunday before`() {
        val start = HistoryStats.startOfWeek(now, zone, DayOfWeek.SUNDAY)
        assertEquals(aug(30, 0).toInstant(), start)
    }

    @Test
    fun `a week starting Monday begins on the Monday before`() {
        val start = HistoryStats.startOfWeek(now, zone, DayOfWeek.MONDAY)
        assertEquals(aug(31, 0).toInstant(), start)
    }

    @Test
    fun `a week that starts today begins at midnight today`() {
        val start = HistoryStats.startOfWeek(now, zone, DayOfWeek.THURSDAY)
        assertEquals(sep(3, 0).toInstant(), start)
    }

    @Test
    fun `this week counts only runs since the start of the week`() {
        val runs = listOf(
            run(1, sep(3, 8)),            // Thursday, this week
            run(2, sep(1, 8)),            // Tuesday, this week
            run(3, aug(28, 8)),          // Friday 28 Aug — the week before
        )
        val week = HistoryStats.weekTotals(runs, now, zone, DayOfWeek.SUNDAY)
        assertEquals(2, week.runCount)
        assertEquals(2 * 1609.344, week.distanceMeters, 1e-9)
    }

    @Test
    fun `a run in the future is still counted this week`() {
        // Clock skew or a run finished a minute from now should not vanish.
        val runs = listOf(run(1, sep(4, 8)))
        assertEquals(1, HistoryStats.weekTotals(runs, now, zone, DayOfWeek.SUNDAY).runCount)
    }

    @Test
    fun `a run just before local midnight on the first day of the week counts`() {
        val runs = listOf(run(1, aug(30, 23)))
        assertEquals(1, HistoryStats.weekTotals(runs, now, zone, DayOfWeek.SUNDAY).runCount)
    }

    @Test
    fun `average pace over the totals uses total distance and total time`() {
        val totals = HistoryStats.totals(
            listOf(
                run(1, sep(1, 8), distanceMeters = 1609.344, movingDurationSec = 480),
                run(2, sep(2, 8), distanceMeters = 1609.344, movingDurationSec = 720),
            ),
        )
        assertEquals(600.0, totals.avgPaceSecPerMile, 1e-6)
    }

    @Test
    fun `average pace of no distance is not a number`() {
        assertEquals(Double.NaN, HistoryStats.totals(emptyList()).avgPaceSecPerMile, 0.0)
    }
}
