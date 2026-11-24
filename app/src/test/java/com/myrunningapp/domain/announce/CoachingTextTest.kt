package com.myrunningapp.domain.announce

import com.myrunningapp.domain.Units
import com.myrunningapp.domain.announce.Announcement.PaceVerdict
import com.myrunningapp.domain.plan.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The coach's lines, as heard. */
class CoachingTextTest {

    private fun say(a: Announcement) = AnnouncementText.of(a)

    @Test
    fun `the intro reads the workout, distance and pace range`() {
        assertEquals(
            "Today's workout: Long run. 6 miles. Target pace, 11 minutes to 11 minutes 30 seconds per mile.",
            say(Announcement.WorkoutIntro("Long run", Units.milesToMeters(6.0), null, 660.0, 690.0)),
        )
    }

    @Test
    fun `an intro with one pace and a time reads both plainly`() {
        assertEquals(
            "Today's workout: Tempo. 30 minutes. Target pace, 9 minutes per mile.",
            say(Announcement.WorkoutIntro("Tempo.", null, 1800, 540.0, 540.0)),
        )
    }

    @Test
    fun `an intro with no targets is just the name`() {
        assertEquals(
            "Today's workout: Shakeout.",
            say(Announcement.WorkoutIntro("Shakeout", null, null, null, null)),
        )
    }

    private fun checkIn(
        verdict: PaceVerdict?,
        off: Long = 0,
        meters: Double? = null,
        sec: Long? = null,
        type: WorkoutType = WorkoutType.LONG,
    ) = say(Announcement.WorkoutCheckIn(type, verdict, off, meters, sec))

    @Test
    fun `check-ins combine the verdict and the distance left`() {
        assertEquals("Right on target pace. 4 miles to go.", checkIn(PaceVerdict.ON_TARGET, meters = Units.milesToMeters(4.0)))
        assertEquals(
            "20 seconds faster than target. Ease off a little. 2.5 miles to go.",
            checkIn(PaceVerdict.TOO_FAST, 20, meters = Units.milesToMeters(2.5)),
        )
        assertEquals("1 second slower than target. That's okay, keep it steady.", checkIn(PaceVerdict.TOO_SLOW, 1))
    }

    @Test
    fun `race day is coached harder`() {
        assertEquals(
            "15 seconds slower than target. Pick it up if you can.",
            checkIn(PaceVerdict.TOO_SLOW, 15, type = WorkoutType.RACE),
        )
        assertEquals(
            "10 seconds faster than target. Save some for later.",
            checkIn(PaceVerdict.TOO_FAST, 10, type = WorkoutType.RACE),
        )
    }

    @Test
    fun `time left is rounded up to whole minutes`() {
        assertEquals("12 minutes to go.", checkIn(null, sec = 11 * 60 + 5))
        assertEquals("Halfway. 20 minutes to go.", say(Announcement.WorkoutHalfway(1200)))
    }

    @Test
    fun `the target line says what was done`() {
        assertEquals(
            "Workout complete. 6 miles done. Great work. Cool down, then press finish.",
            say(Announcement.WorkoutTargetReached(Units.milesToMeters(6.0), null)),
        )
        assertEquals(
            "Workout complete. 45 minutes done. Great work. Cool down, then press finish.",
            say(Announcement.WorkoutTargetReached(null, 2700)),
        )
    }
}
