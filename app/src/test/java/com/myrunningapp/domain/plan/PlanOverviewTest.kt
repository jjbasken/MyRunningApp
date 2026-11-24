package com.myrunningapp.domain.plan

import com.myrunningapp.domain.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class PlanOverviewTest {

    /** A Thursday. */
    private val today = LocalDate.of(2026, 11, 19)

    private fun plan(raceDate: LocalDate? = LocalDate.of(2027, 11, 14)) =
        TrainingPlan(1, "Half", raceDate, Units.milesToMeters(13.1), Instant.EPOCH)

    private var nextId = 1L

    private fun w(
        daysFromToday: Long,
        type: WorkoutType = WorkoutType.EASY,
        status: WorkoutStatus = WorkoutStatus.PLANNED,
        miles: Double = 3.0,
        fast: Double? = 690.0,
        ranPace: Double? = null,
        ranMiles: Double? = null,
    ) = PlannedWorkout(
        id = nextId++, planId = 1, date = today.plusDays(daysFromToday), type = type, title = type.name,
        description = "", distanceMeters = Units.milesToMeters(miles), durationSec = null,
        paceFastSecPerMile = fast, paceSlowSecPerMile = fast?.plus(60),
        status = status, completedRunId = null,
        completedDistanceMeters = ranMiles?.let(Units::milesToMeters), completedPaceSecPerMile = ranPace,
    )

    private fun overview(workouts: List<PlannedWorkout>, raceDate: LocalDate? = LocalDate.of(2027, 11, 14)) =
        PlanOverview.of(plan(raceDate), workouts, today)

    @Test
    fun `today's run is the planned run on today's date`() {
        val run = w(0)
        val o = overview(listOf(w(-1, status = WorkoutStatus.COMPLETED), run, w(2)))
        assertEquals(run.id, o.todaysRun!!.id)
        assertEquals(today.plusDays(2), o.nextRun!!.date)
    }

    @Test
    fun `a run already done today is not offered again`() {
        assertNull(overview(listOf(w(0, status = WorkoutStatus.COMPLETED))).todaysRun)
    }

    @Test
    fun `a rest day is today but not today's run`() {
        val o = overview(listOf(w(-3), w(0, type = WorkoutType.REST)))
        assertNull(o.todaysRun)
        assertEquals(1, o.today.size)
        assertTrue(CoachTip.RestDay in o.tips)
    }

    @Test
    fun `this week's miles run Monday to Sunday and count what was actually run`() {
        val o = overview(
            listOf(
                w(-4, miles = 99.0), // the Sunday before: last week
                w(-3, status = WorkoutStatus.COMPLETED, miles = 3.0, ranMiles = 3.4), // Monday
                w(0, miles = 4.0),
                w(3, miles = 6.0), // Sunday
            ),
        )
        assertEquals(Units.milesToMeters(13.0), o.weekPlannedMeters, 0.01)
        assertEquals(Units.milesToMeters(3.4), o.weekCompletedMeters, 0.01)
    }

    @Test
    fun `missed runs in the last week are called out, skipped ones are not`() {
        val o = overview(
            listOf(
                w(-10), // too long ago to nag about
                w(-5),
                w(-2),
                w(-1, status = WorkoutStatus.SKIPPED),
                w(1),
            ),
        )
        assertEquals(CoachTip.MissedRuns(2), o.tips.first())
        assertTrue(o.tips.none { it is CoachTip.OnTrack })
    }

    @Test
    fun `easy runs well ahead of target are flagged`() {
        val o = overview(
            listOf(
                w(-6, status = WorkoutStatus.COMPLETED, fast = 690.0, ranPace = 640.0),
                w(-4, status = WorkoutStatus.COMPLETED, fast = 690.0, ranPace = 650.0),
                w(-2, status = WorkoutStatus.COMPLETED, fast = 690.0, ranPace = 685.0),
            ),
        )
        assertTrue(CoachTip.EasyRunsTooFast(2) in o.tips)
    }

    @Test
    fun `one quick easy run is not a pattern`() {
        val o = overview(
            listOf(
                w(-4, status = WorkoutStatus.COMPLETED, ranPace = 600.0),
                w(-2, status = WorkoutStatus.COMPLETED, ranPace = 700.0),
            ),
        )
        assertTrue(o.tips.none { it is CoachTip.EasyRunsTooFast })
    }

    @Test
    fun `a long run today gets its own advice`() {
        assertTrue(CoachTip.LongRunToday in overview(listOf(w(-1, status = WorkoutStatus.COMPLETED), w(0, WorkoutType.LONG))).tips)
    }

    @Test
    fun `an up-to-date runner is told so`() {
        val o = overview(
            listOf(
                w(-3, status = WorkoutStatus.COMPLETED),
                w(-1, status = WorkoutStatus.COMPLETED),
                w(-2, status = WorkoutStatus.SKIPPED),
                w(0),
            ),
        )
        assertEquals(CoachTip.OnTrack(completed = 2, due = 2), o.tips.last())
    }

    @Test
    fun `runs missed before this week still stop the plan being called on track`() {
        val o = overview(
            listOf(
                w(-20), w(-18), w(-16), w(-14), w(-12), w(-10), w(-9),
                w(-4, status = WorkoutStatus.COMPLETED),
                w(-2, status = WorkoutStatus.COMPLETED),
                w(-1, status = WorkoutStatus.COMPLETED),
            ),
        )
        assertTrue(o.tips.none { it is CoachTip.OnTrack || it is CoachTip.MissedRuns })
        assertEquals(CoachTip.BehindPlan(completed = 3, due = 10), o.tips.last())
    }

    @Test
    fun `an old miss or two still counts as on track`() {
        val done = (1L..8L).map { w(-it - 1, status = WorkoutStatus.COMPLETED) }
        val o = overview(done + w(-20) + w(-22))
        assertEquals(CoachTip.OnTrack(completed = 8, due = 10), o.tips.last())
    }

    @Test
    fun `race week outranks everything`() {
        val o = overview(listOf(w(-2), w(3, WorkoutType.RACE)), raceDate = today.plusDays(3))
        assertEquals(CoachTip.RaceWeek(3), o.tips.first())
        assertEquals(3L, o.daysToRace)
    }

    @Test
    fun `the two weeks before race week are the taper`() {
        val o = overview(listOf(w(-1, status = WorkoutStatus.COMPLETED), w(14, WorkoutType.RACE)), raceDate = today.plusDays(14))
        assertEquals(CoachTip.Taper(14), o.tips.first())
    }

    @Test
    fun `a plan that has not begun says when it does`() {
        assertEquals(listOf(CoachTip.NotStarted(4)), overview(listOf(w(4), w(6))).tips)
    }

    @Test
    fun `a plan past its race is finished`() {
        assertEquals(listOf(CoachTip.PlanFinished), overview(listOf(w(-30)), raceDate = today.minusDays(1)).tips)
    }

    @Test
    fun `progress counts runs, not rest days`() {
        val o = overview(
            listOf(
                w(-2, status = WorkoutStatus.COMPLETED),
                w(-1, type = WorkoutType.REST),
                w(1),
            ),
        )
        assertEquals(1, o.completedRuns)
        assertEquals(2, o.totalRuns)
    }
}
