package com.myrunningapp.domain.plan

import com.myrunningapp.domain.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Plans arrive by copy and paste from a chat reply, so these cover the shapes a
 * reply actually takes: fenced, prefaced, trailing-commaed, grouped by week.
 */
class PlanParserTest {

    /** A Thursday, so "the coming Monday" is not today. */
    private val today = LocalDate.of(2026, 11, 19)

    private fun parse(text: String) = PlanParser.parse(text, today)

    private fun failure(text: String): PlanImportFailure = try {
        parse(text)
        fail("expected the plan to be rejected")
        throw AssertionError()
    } catch (e: PlanImportException) {
        e.failure
    }

    @Test
    fun `week and day count from the Monday of the start date`() {
        val plan = parse(
            """
            {"name": "Half", "startDate": "2026-11-23", "workouts": [
              {"week": 1, "day": "Tue", "type": "easy", "distanceMiles": 3},
              {"week": 2, "day": "Saturday", "type": "long", "distanceMiles": 5}
            ]}
            """,
        )
        assertEquals("Half", plan.name)
        assertEquals(LocalDate.of(2026, 11, 24), plan.workouts[0].date)
        assertEquals(LocalDate.of(2026, 12, 5), plan.workouts[1].date)
        assertEquals(WorkoutType.LONG, plan.workouts[1].type)
        assertEquals(Units.milesToMeters(5.0), plan.workouts[1].distanceMeters!!, 0.01)
    }

    @Test
    fun `a start date mid-week still anchors week one on its Monday`() {
        val plan = parse(
            """{"startDate": "2026-11-26", "workouts": [{"week": 1, "day": "Mon", "distanceMiles": 2}]}""",
        )
        assertEquals(LocalDate.of(2026, 11, 23), plan.workouts.single().date)
    }

    @Test
    fun `without a start date the last week is counted back from the race`() {
        val plan = parse(
            """
            {"raceDate": "2027-11-14", "workouts": [
              {"week": 1, "day": "Mon", "distanceMiles": 2},
              {"week": 52, "day": "Sun", "type": "race", "distanceMiles": 13.1}
            ]}
            """,
        )
        assertEquals(LocalDate.of(2027, 11, 14), plan.workouts.last().date)
        assertEquals(DayOfWeek.MONDAY, plan.workouts.first().date.dayOfWeek)
        assertEquals(LocalDate.of(2026, 11, 16), plan.workouts.first().date)
        assertEquals(LocalDate.of(2027, 11, 14), plan.raceDate)
    }

    @Test
    fun `with no dates at all the plan starts next Monday`() {
        val plan = parse("""{"workouts": [{"week": 1, "day": 1, "distanceMiles": 2}]}""")
        assertEquals(LocalDate.of(2026, 11, 23), plan.workouts.single().date)
    }

    @Test
    fun `explicit dates are used as they are`() {
        val plan = parse("""{"workouts": [{"date": "2026-12-25", "title": "Christmas jog", "durationMinutes": 30}]}""")
        val workout = plan.workouts.single()
        assertEquals(LocalDate.of(2026, 12, 25), workout.date)
        assertEquals("Christmas jog", workout.title)
        assertEquals(1800L, workout.durationSec)
        assertNull(workout.distanceMeters)
    }

    @Test
    fun `a reply with prose and a code fence is still read`() {
        val plan = parse(
            """
            Here's your plan! Good luck with the race.

            ```json
            {"name": "Fenced", "workouts": [{"week": 1, "day": "Wed", "distanceKm": 5}]}
            ```

            Let me know if you want changes.
            """,
        )
        assertEquals("Fenced", plan.name)
        assertEquals(5000.0, plan.workouts.single().distanceMeters!!, 0.01)
    }

    @Test
    fun `trailing commas and comments are tolerated`() {
        val plan = parse(
            """
            {
              // the first week
              "workouts": [
                {"week": 1, "day": "Mon", "distanceMiles": 2,},
              ],
            }
            """,
        )
        assertEquals(1, plan.workouts.size)
    }

    @Test
    fun `workouts grouped under weeks inherit the week number`() {
        val plan = parse(
            """
            {"startDate": "2026-11-23", "weeks": [
              {"week": 1, "workouts": [{"day": "Tue", "distanceMiles": 3}]},
              {"week": 3, "workouts": [{"day": "Tue", "distanceMiles": 4}]}
            ]}
            """,
        )
        assertEquals(listOf(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 8)), plan.workouts.map { it.date })
    }

    @Test
    fun `a bare array of dated workouts is a plan`() {
        val plan = parse("""[{"date": "2026-12-01", "distanceMiles": 3}]""")
        assertEquals("Training plan", plan.name)
        assertEquals(1, plan.workouts.size)
    }

    @Test
    fun `numbers written as strings are read`() {
        val plan = parse("""{"workouts": [{"week": "1", "day": "Mon", "distanceMiles": "3.5"}]}""")
        assertEquals(Units.milesToMeters(3.5), plan.workouts.single().distanceMeters!!, 0.01)
    }

    @Test
    fun `workouts come back in date order`() {
        val plan = parse(
            """
            {"startDate": "2026-11-23", "workouts": [
              {"week": 2, "day": "Mon", "distanceMiles": 3},
              {"week": 1, "day": "Fri", "distanceMiles": 3}
            ]}
            """,
        )
        assertTrue(plan.workouts[0].date < plan.workouts[1].date)
    }

    // --- paces ---------------------------------------------------------------------

    private fun pace(text: String): Pair<Double?, Double?> {
        val w = parse("""{"workouts": [{"date": "2026-12-01", "distanceMiles": 3, "targetPace": "$text"}]}""")
            .workouts.single()
        return w.paceFastSecPerMile to w.paceSlowSecPerMile
    }

    @Test
    fun `a pace range is read fast end first`() {
        assertEquals(630.0 to 660.0, pace("10:30-11:00"))
        assertEquals(630.0 to 660.0, pace("11:00 – 10:30 min/mile"))
    }

    @Test
    fun `a single pace is both ends of the range`() {
        assertEquals(570.0 to 570.0, pace("9:30/mi"))
    }

    @Test
    fun `a per-kilometre pace is converted to per mile`() {
        val (fast, slow) = pace("6:00/km")
        assertEquals(360.0 * Units.METERS_PER_MILE / 1000.0, fast!!, 0.5)
        assertEquals(fast, slow!!, 0.0)
    }

    @Test
    fun `a pace given in both units is read in the first one`() {
        assertEquals(570.0 to 600.0, pace("9:30-10:00 /mi (5:54-6:13 /km)"))
        assertEquals(570.0 to 570.0, pace("9:30/mi, about 5:54/km"))
    }

    @Test
    fun `a per-kilometre range followed by its mile equivalent stays per kilometre`() {
        val (fast, slow) = pace("5:30 to 6:00 per km (8:51-9:39 /mi)")
        assertEquals(330.0 / (1000.0 / Units.METERS_PER_MILE), fast!!, 0.5)
        assertEquals(360.0 / (1000.0 / Units.METERS_PER_MILE), slow!!, 0.5)
    }

    @Test
    fun `a pace by feel has no numbers to coach to`() {
        assertEquals(null to null, pace("conversational"))
    }

    @Test
    fun `an absurd pace is rejected`() {
        assertEquals(
            PlanImportFailure.BAD_WORKOUT,
            failure("""{"workouts": [{"date": "2026-12-01", "targetPace": "1:30"}]}"""),
        )
    }

    // --- types -------------------------------------------------------------------------

    @Test
    fun `the many names for a session map onto the types the coach knows`() {
        assertEquals(WorkoutType.EASY, PlanParser.parseType("Easy Run"))
        assertEquals(WorkoutType.LONG, PlanParser.parseType("long_run"))
        assertEquals(WorkoutType.TEMPO, PlanParser.parseType("Threshold"))
        assertEquals(WorkoutType.INTERVALS, PlanParser.parseType("speed work"))
        assertEquals(WorkoutType.INTERVALS, PlanParser.parseType("Hill repeats"))
        assertEquals(WorkoutType.INTERVALS, PlanParser.parseType("fartlek"))
        assertEquals(WorkoutType.RECOVERY, PlanParser.parseType("recovery"))
        assertEquals(WorkoutType.RACE, PlanParser.parseType("Race Day"))
        assertEquals(WorkoutType.RACE, PlanParser.parseType("Tune-up 10K race"))
        assertEquals(WorkoutType.RECOVERY, PlanParser.parseType("Pre-race shakeout"))
        assertEquals(WorkoutType.RECOVERY, PlanParser.parseType("pre-race jog"))
        assertEquals(WorkoutType.TEMPO, PlanParser.parseType("Goal race pace miles"))
        assertEquals(WorkoutType.REST, PlanParser.parseType("rest"))
        assertEquals(WorkoutType.CROSS_TRAIN, PlanParser.parseType("Strength training"))
        assertEquals(WorkoutType.EASY, PlanParser.parseType(null))
        assertEquals(WorkoutType.OTHER, PlanParser.parseType("parkrun"))
    }

    @Test
    fun `the title stands in for a missing type`() {
        val w = parse("""{"workouts": [{"date": "2026-12-01", "title": "Tempo 3 mi"}]}""").workouts.single()
        assertEquals(WorkoutType.TEMPO, w.type)
    }

    @Test
    fun `a rest day carries no targets`() {
        val w = parse(
            """{"workouts": [{"date": "2026-12-01", "type": "rest", "distanceMiles": 3, "targetPace": "9:00"}]}""",
        ).workouts.single()
        assertEquals("Rest day", w.title)
        assertNull(w.distanceMeters)
        assertNull(w.paceFastSecPerMile)
    }

    // --- failures -----------------------------------------------------------------------

    @Test
    fun `text with no JSON is not a plan`() {
        assertEquals(PlanImportFailure.NOT_JSON, failure("Sure! Here is a plan: run a lot."))
    }

    @Test
    fun `a plan with no workouts is rejected`() {
        assertEquals(PlanImportFailure.NO_WORKOUTS, failure("""{"name": "Empty", "workouts": []}"""))
    }

    @Test
    fun `a workout with neither a date nor a week and day is rejected`() {
        assertEquals(PlanImportFailure.BAD_DATE, failure("""{"workouts": [{"distanceMiles": 3}]}"""))
    }

    @Test
    fun `a malformed date is rejected`() {
        assertEquals(PlanImportFailure.BAD_DATE, failure("""{"workouts": [{"date": "next Tuesday"}]}"""))
    }

    @Test
    fun `a distance no plan would set is rejected`() {
        assertEquals(
            PlanImportFailure.BAD_WORKOUT,
            failure("""{"workouts": [{"date": "2026-12-01", "distanceMiles": 500}]}"""),
        )
    }

    // --- distances written the way assistants write them ---------------------------------

    private fun distance(json: String): Double? =
        parse("""{"workouts": [{"date": "2026-12-01", $json}]}""").workouts.single().distanceMeters

    @Test
    fun `a distance with its unit spelled out in the value is read`() {
        assertEquals(Units.milesToMeters(3.0), distance("\"distanceMiles\": \"3 miles\"")!!, 0.01)
        assertEquals(Units.milesToMeters(5.0), distance("\"distance\": 5")!!, 0.01)
        assertEquals(Units.milesToMeters(3.1), distance("\"distance\": \"3.1 mi\"")!!, 0.01)
        assertEquals(5000.0, distance("\"distance\": \"5 km\"")!!, 0.01)
        assertEquals(5000.0, distance("\"distance\": \"5K\"")!!, 0.01)
        assertEquals(800.0, distance("\"distance\": \"800m\"")!!, 0.01)
    }

    @Test
    fun `a session description in the distance field is not taken for a distance`() {
        assertNull(distance("\"distance\": \"6 x 400m\""))
        assertNull(distance("\"distance\": \"about 5 miles\""))
    }

    // --- restoring the app's own backup ---------------------------------------------------

    @Test
    fun `statuses from a backup come back, but run links do not`() {
        val plan = parse(
            """
            {"workouts": [
              {"date": "2026-11-16", "type": "easy", "status": "completed", "completedRunId": 7},
              {"date": "2026-11-17", "type": "easy", "status": "skipped"},
              {"date": "2026-11-18", "type": "easy", "status": "planned"},
              {"date": "2026-11-20", "type": "easy"}
            ]}
            """,
        )
        assertEquals(
            listOf(WorkoutStatus.COMPLETED, WorkoutStatus.SKIPPED, WorkoutStatus.PLANNED, WorkoutStatus.PLANNED),
            plan.workouts.map { it.status },
        )
    }

    // --- the prompt's own example --------------------------------------------------------

    @Test
    fun `the example in the AI prompt is itself an importable plan`() {
        val prompt = PlanPrompt.build(PlanRequest(raceDate = LocalDate.of(2027, 11, 14)), today)
        val plan = parse(prompt)
        assertEquals(3, plan.workouts.size)
        assertEquals(LocalDate.of(2026, 11, 24), plan.workouts.first().date)
        assertEquals(LocalDate.of(2027, 11, 14), plan.raceDate)
        assertEquals(Units.milesToMeters(13.1), plan.raceDistanceMeters!!, 0.01)
        assertEquals("Half marathon plan", plan.name)
    }

    @Test
    fun `the prompt names the race and tapers for its distance`() {
        fun prompt(miles: Double) =
            PlanPrompt.build(PlanRequest(raceDate = LocalDate.of(2027, 11, 14), raceDistanceMiles = miles), today)

        val fiveK = prompt(3.1)
        assertTrue("complete 5K training plan" in fiveK)
        assertTrue("Taper over the final week." in fiveK)
        assertEquals("5K plan", parse(fiveK).name)
        assertEquals(Units.milesToMeters(3.1), parse(fiveK).raceDistanceMeters!!, 0.01)

        assertTrue("Taper over the last 2 weeks." in prompt(13.1))

        val marathon = prompt(26.2)
        assertTrue("complete marathon training plan" in marathon)
        assertTrue("Taper over the last 3 weeks." in marathon)

        val custom = prompt(8.0)
        assertTrue("complete 8-mile race training plan" in custom)
        assertEquals(Units.milesToMeters(8.0), parse(custom).raceDistanceMeters!!, 0.01)
    }

    @Test
    fun `a race before the coming Monday starts the plan this week, not after the race`() {
        // today is Thursday 2026-11-19; the race is that Sunday.
        val prompt = PlanPrompt.build(PlanRequest(raceDate = LocalDate.of(2026, 11, 22), raceDistanceMiles = 3.1), today)
        assertTrue("Plan starts: 2026-11-16 (a Monday) — that is 1 week of training" in prompt)
        assertEquals(LocalDate.of(2026, 11, 17), parse(prompt).workouts.first().date)
    }

    @Test
    fun `race presets are recognised to a tenth of a mile`() {
        assertEquals(RaceDistance.TEN_K, RaceDistance.of(6.2))
        assertEquals(RaceDistance.MARATHON, RaceDistance.of(26.2))
        assertNull(RaceDistance.of(8.0))
    }
}
