package com.myrunningapp.domain.plan

import com.myrunningapp.domain.Units
import com.myrunningapp.domain.announce.Announcement
import com.myrunningapp.domain.announce.Announcement.PaceVerdict
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.RunSessionState
import com.myrunningapp.domain.tracking.MileSplit
import com.myrunningapp.domain.tracking.RunSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WorkoutCoachTest {

    private fun workout(
        type: WorkoutType = WorkoutType.LONG,
        miles: Double? = 6.0,
        minutes: Long? = null,
        fast: Double? = 660.0,
        slow: Double? = 690.0,
        status: WorkoutStatus = WorkoutStatus.PLANNED,
    ) = PlannedWorkout(
        id = 9, planId = 1, date = LocalDate.of(2026, 12, 5), type = type, title = "Long run",
        description = "", distanceMeters = miles?.let(Units::milesToMeters), durationSec = minutes?.times(60),
        paceFastSecPerMile = fast, paceSlowSecPerMile = slow, status = status, completedRunId = null,
    )

    private fun mile(number: Int, pace: Double) = MileSplit(
        splitNumber = number, distanceMeters = Units.METERS_PER_MILE, durationSec = pace.toLong(),
        paceSecPerMile = pace, cumulativeMovingSec = (number * pace).toLong(),
    )

    private fun tracking(miles: Double, movingSec: Long) = RunSnapshot.idle(ActivityType.RUN).copy(
        state = RunSessionState.TRACKING,
        distanceMeters = Units.milesToMeters(miles),
        movingDurationSec = movingSec,
    )

    private fun checkIn(coach: WorkoutCoach, number: Int, pace: Double) =
        coach.onMile(mile(number, pace)) as Announcement.WorkoutCheckIn

    @Test
    fun `the intro states the workout, its distance and its pace`() {
        val intro = WorkoutCoach(workout()).intro() as Announcement.WorkoutIntro
        assertEquals("Long run", intro.title)
        assertEquals(Units.milesToMeters(6.0), intro.distanceMeters!!, 0.01)
        assertEquals(660.0, intro.paceFastSecPerMile!!, 0.0)
        assertEquals(690.0, intro.paceSlowSecPerMile!!, 0.0)
    }

    @Test
    fun `a mile inside the range is on target`() {
        val c = checkIn(WorkoutCoach(workout()), 1, pace = 675.0)
        assertEquals(PaceVerdict.ON_TARGET, c.verdict)
    }

    @Test
    fun `a few seconds outside the range is still on target`() {
        assertEquals(PaceVerdict.ON_TARGET, checkIn(WorkoutCoach(workout()), 1, pace = 656.0).verdict)
        assertEquals(PaceVerdict.ON_TARGET, checkIn(WorkoutCoach(workout()), 1, pace = 694.0).verdict)
    }

    @Test
    fun `a fast mile says by how much`() {
        val c = checkIn(WorkoutCoach(workout()), 1, pace = 640.0)
        assertEquals(PaceVerdict.TOO_FAST, c.verdict)
        assertEquals(20L, c.secondsOff)
    }

    @Test
    fun `a slow mile on a long run says by how much`() {
        val c = checkIn(WorkoutCoach(workout()), 1, pace = 720.0)
        assertEquals(PaceVerdict.TOO_SLOW, c.verdict)
        assertEquals(30L, c.secondsOff)
    }

    @Test
    fun `slow is never a fault on an easy day`() {
        val c = checkIn(WorkoutCoach(workout(type = WorkoutType.EASY)), 1, pace = 800.0)
        assertNull(c.verdict)
        // Still worth saying how far is left.
        assertEquals(Units.milesToMeters(5.0), c.remainingMeters!!, 0.01)
    }

    @Test
    fun `interval sessions get no pace verdict and no paced intro`() {
        val coach = WorkoutCoach(workout(type = WorkoutType.INTERVALS))
        assertNull((coach.intro() as Announcement.WorkoutIntro).paceFastSecPerMile)
        assertNull(checkIn(coach, 1, pace = 500.0).verdict)
    }

    @Test
    fun `each mile counts down what is left`() {
        val coach = WorkoutCoach(workout())
        assertEquals(Units.milesToMeters(4.0), checkIn(coach, 2, 675.0).remainingMeters!!, 0.01)
    }

    @Test
    fun `the last mile does not say zero miles to go`() {
        val c = checkIn(WorkoutCoach(workout()), 6, pace = 675.0)
        assertNull(c.remainingMeters)
        assertEquals(PaceVerdict.ON_TARGET, c.verdict)
    }

    @Test
    fun `a mile with nothing to say says nothing`() {
        val coach = WorkoutCoach(workout(type = WorkoutType.INTERVALS, miles = null, fast = null, slow = null))
        assertNull(coach.onMile(mile(1, 600.0)))
    }

    @Test
    fun `reaching the distance is announced exactly once`() {
        val coach = WorkoutCoach(workout())
        assertNull(coach.onProgress(tracking(miles = 5.9, movingSec = 3900)))
        val reached = coach.onProgress(tracking(miles = 6.0, movingSec = 3960))
        assertTrue(reached is Announcement.WorkoutTargetReached)
        assertNull(coach.onProgress(tracking(miles = 6.2, movingSec = 4100)))
    }

    @Test
    fun `once the target is reached later miles stay quiet`() {
        val coach = WorkoutCoach(workout(miles = 1.0))
        coach.onProgress(tracking(miles = 1.0, movingSec = 660))
        assertNull(coach.onMile(mile(2, 640.0)))
    }

    @Test
    fun `a workout set by time gets halfway and then done`() {
        val coach = WorkoutCoach(workout(miles = null, minutes = 40, fast = null, slow = null))
        assertNull(coach.onProgress(tracking(miles = 1.0, movingSec = 1100)))
        val halfway = coach.onProgress(tracking(miles = 2.0, movingSec = 1200)) as Announcement.WorkoutHalfway
        assertEquals(1200L, halfway.remainingSec)
        assertNull(coach.onProgress(tracking(miles = 2.1, movingSec = 1260)))
        val done = coach.onProgress(tracking(miles = 4.0, movingSec = 2400)) as Announcement.WorkoutTargetReached
        assertEquals(2400L, done.durationSec)
        assertNull(done.distanceMeters)
    }

    @Test
    fun `time left is counted down at each mile of a timed workout`() {
        val coach = WorkoutCoach(workout(miles = null, minutes = 40, fast = null, slow = null))
        assertEquals(1800L, checkIn(coach, 1, pace = 600.0).remainingSec)
    }

    @Test
    fun `a distance wins over a time when the plan gives both`() {
        val coach = WorkoutCoach(workout(miles = 3.0, minutes = 30))
        assertNull(coach.onProgress(tracking(miles = 2.5, movingSec = 1900)))
        assertTrue(coach.onProgress(tracking(miles = 3.0, movingSec = 2000)) is Announcement.WorkoutTargetReached)
    }

    @Test
    fun `nothing is said while paused`() {
        val coach = WorkoutCoach(workout(miles = 1.0))
        assertNull(coach.onProgress(tracking(miles = 1.5, movingSec = 900).copy(state = RunSessionState.PAUSED)))
    }

    @Test
    fun `only planned runs are coached`() {
        assertTrue(WorkoutCoach.coaches(workout()))
        assertFalse(WorkoutCoach.coaches(workout(type = WorkoutType.REST)))
        assertFalse(WorkoutCoach.coaches(workout(type = WorkoutType.CROSS_TRAIN)))
        assertFalse(WorkoutCoach.coaches(workout(status = WorkoutStatus.COMPLETED)))
    }
}
