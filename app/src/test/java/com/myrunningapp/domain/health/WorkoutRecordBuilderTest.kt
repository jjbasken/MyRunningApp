package com.myrunningapp.domain.health

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.RunPoint
import com.myrunningapp.domain.model.Split
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WorkoutRecordBuilderTest {

    private val t0: Instant = Instant.parse("2026-09-09T12:00:00Z")

    private fun run(
        activityType: ActivityType = ActivityType.RUN,
        distanceMeters: Double = 3218.68,
        movingDurationSec: Long = 1080,
        elapsedDurationSec: Long = 1200,
    ) = Run(
        id = 42, startedAt = t0, endedAt = t0.plusSeconds(elapsedDurationSec),
        activityType = activityType, distanceMeters = distanceMeters,
        movingDurationSec = movingDurationSec, elapsedDurationSec = elapsedDurationSec,
        avgPaceSecPerMile = 540.0, calories = 320, weightKgAtRun = 70.0,
    )

    private fun point(offsetSec: Long, segmentIndex: Int = 0) = RunPoint(
        id = 0, runId = 42, timestamp = t0.plusSeconds(offsetSec),
        latitude = 40.0 + offsetSec / 100_000.0, longitude = -105.0,
        altitudeMeters = 1600.0, accuracyMeters = 5f, segmentIndex = segmentIndex,
    )

    private fun split(number: Int, durationSec: Long, distanceMeters: Double = 1609.34) =
        Split(
            id = number.toLong(), runId = 42, splitNumber = number,
            distanceMeters = distanceMeters, durationSec = durationSec,
            paceSecPerMile = durationSec.toDouble(),
        )

    @Test
    fun `client record id is the run id`() {
        val workout = WorkoutRecordBuilder.build(run(), emptyList(), listOf(point(0)), false, clientRecordVersion = 1L)

        assertEquals("run-42", workout!!.clientRecordId)
    }

    @Test
    fun `the session spans elapsed time, pauses included`() {
        val workout = WorkoutRecordBuilder.build(run(), emptyList(), listOf(point(0)), false, clientRecordVersion = 1L)!!

        assertEquals(t0, workout.startedAt)
        assertEquals(t0.plusSeconds(1200), workout.endedAt)
    }

    @Test
    fun `a walk is built as a walk`() {
        val workout = WorkoutRecordBuilder
            .build(
                run(activityType = ActivityType.WALK), emptyList(), listOf(point(0)), false,
                clientRecordVersion = 1L,
            )!!

        assertEquals(ActivityType.WALK, workout.activityType)
    }

    @Test
    fun `calories map to the run's estimate`() {
        val workout = WorkoutRecordBuilder.build(run(), emptyList(), listOf(point(0)), false, clientRecordVersion = 1L)!!

        assertEquals(320, workout.activeCalories)
    }

    @Test
    fun `pause gaps become separate segments`() {
        val points = listOf(point(0), point(100), point(400, 1), point(1200, 1))

        val workout = WorkoutRecordBuilder.build(run(), emptyList(), points, false, clientRecordVersion = 1L)!!

        assertEquals(2, workout.segments.size)
        assertEquals(t0.plusSeconds(100), workout.segments[0].endedAt)
        assertEquals(t0.plusSeconds(400), workout.segments[1].startedAt)
    }

    @Test
    fun `each split becomes a lap laid on the moving timeline`() {
        val splits = listOf(split(1, 540), split(2, 540))
        val points = listOf(point(0), point(1080))

        val workout = WorkoutRecordBuilder.build(run(), splits, points, false, clientRecordVersion = 1L)!!

        assertEquals(2, workout.laps.size)
        assertEquals(t0, workout.laps[0].startedAt)
        assertEquals(t0.plusSeconds(540), workout.laps[0].endedAt)
        assertEquals(t0.plusSeconds(540), workout.laps[1].startedAt)
        assertEquals(t0.plusSeconds(1080), workout.laps[1].endedAt)
    }

    @Test
    fun `laps stay inside the session when the run was paused`() {
        val splits = listOf(split(1, 540), split(2, 540))
        // Moving time is 1080 s but the run took 1200 s: a 120 s pause at 540 s.
        val points = listOf(point(0), point(540), point(660, 1), point(1200, 1))

        val workout = WorkoutRecordBuilder.build(run(), splits, points, false, clientRecordVersion = 1L)!!

        assertTrue(workout.laps.all { !it.startedAt.isBefore(workout.startedAt) })
        assertTrue(workout.laps.all { !it.endedAt.isAfter(workout.endedAt) })
        // The second mile begins after the pause, not during it.
        assertEquals(t0.plusSeconds(660), workout.laps[1].startedAt)
    }

    @Test
    fun `the partial final split becomes a lap with its own shorter distance`() {
        val splits = listOf(split(1, 540), split(2, 200, distanceMeters = 600.0))

        val workout = WorkoutRecordBuilder
            .build(
                run(distanceMeters = 2209.34, movingDurationSec = 740), splits,
                listOf(point(0), point(740)), false, clientRecordVersion = 1L,
            )!!

        assertEquals(600.0, workout.laps[1].distanceMeters, 0.001)
    }

    @Test
    fun `the route is omitted when it is not being shared`() {
        val workout = WorkoutRecordBuilder.build(
            run(), emptyList(), listOf(point(0), point(10)), false, clientRecordVersion = 1L,
        )!!

        assertTrue(workout.route.isEmpty())
    }

    @Test
    fun `the route carries every fix when it is being shared`() {
        val points = listOf(point(0), point(10), point(20))

        val workout = WorkoutRecordBuilder.build(run(), emptyList(), points, true, clientRecordVersion = 1L)!!

        assertEquals(3, workout.route.size)
        assertEquals(t0, workout.route.first().time)
        assertEquals(-105.0, workout.route.first().longitude, 0.0)
    }

    @Test
    fun `a route point exactly on endedAt is excluded`() {
        val elapsed = 1200L
        val points = listOf(point(0), point(elapsed))

        val workout = WorkoutRecordBuilder.build(
            run(elapsedDurationSec = elapsed), emptyList(), points, true, clientRecordVersion = 1L,
        )!!

        assertEquals(1, workout.route.size)
        assertEquals(t0, workout.route.first().time)
    }

    @Test
    fun `a route point exactly on startedAt is kept`() {
        val points = listOf(point(0), point(10))

        val workout = WorkoutRecordBuilder.build(run(), emptyList(), points, true, clientRecordVersion = 1L)!!

        assertTrue(workout.route.any { it.time == t0 })
    }

    @Test
    fun `a zero-duration split produces no lap`() {
        val splits = listOf(split(1, 540), split(2, 0), split(3, 540))
        val points = listOf(point(0), point(1080))

        val workout = WorkoutRecordBuilder
            .build(run(movingDurationSec = 1080), splits, points, false, clientRecordVersion = 1L)!!

        assertEquals(2, workout.laps.size)
        assertEquals(t0, workout.laps[0].startedAt)
        assertEquals(t0.plusSeconds(540), workout.laps[0].endedAt)
        assertEquals(t0.plusSeconds(540), workout.laps[1].startedAt)
        assertEquals(t0.plusSeconds(1080), workout.laps[1].endedAt)
    }

    @Test
    fun `a run that never moved is not worth writing`() {
        val zeroLength = run(distanceMeters = 0.0, movingDurationSec = 0, elapsedDurationSec = 0)

        assertNull(WorkoutRecordBuilder.build(zeroLength, emptyList(), emptyList(), false, clientRecordVersion = 1L))
    }
}
