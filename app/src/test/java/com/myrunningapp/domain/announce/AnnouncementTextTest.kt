package com.myrunningapp.domain.announce

import com.myrunningapp.domain.Units
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wording of every line the app speaks.
 *
 * These read like transcripts on purpose: the announcement is the feature the
 * user actually hears mid-run, and the failure modes here are all small ("1
 * miles", "9 minutes 0 seconds") and all invisible until you are outside.
 */
class AnnouncementTextTest {

    private fun mile(number: Int, totalSec: Long, paceSec: Double) =
        AnnouncementText.of(
            Announcement.MileCompleted(
                mileNumber = number,
                totalMovingSec = totalSec,
                lastMilePaceSec = paceSec,
            ),
        )

    @Test
    fun `the mile line is the one from the design`() {
        assertEquals(
            "3 miles. Time, 27 minutes 42 seconds. Last mile pace, 9 minutes 5 seconds.",
            mile(number = 3, totalSec = 27 * 60 + 42, paceSec = 9 * 60 + 5.0),
        )
    }

    @Test
    fun `the first mile is not announced as one miles`() {
        assertEquals(
            "1 mile. Time, 9 minutes 5 seconds. Last mile pace, 9 minutes 5 seconds.",
            mile(number = 1, totalSec = 545, paceSec = 545.0),
        )
    }

    @Test
    fun `a round pace drops the zero seconds`() {
        assertEquals(
            "2 miles. Time, 18 minutes. Last mile pace, 9 minutes.",
            mile(number = 2, totalSec = 18 * 60, paceSec = 540.0),
        )
    }

    @Test
    fun `an hour into the run the time is spoken in hours`() {
        assertEquals(
            "7 miles. Time, 1 hour 3 minutes 20 seconds. " +
                "Last mile pace, 8 minutes 55 seconds.",
            mile(number = 7, totalSec = 3800, paceSec = 535.0),
        )
    }

    @Test
    fun `a fractional pace is rounded to the nearest second`() {
        assertEquals(
            "1 mile. Time, 8 minutes 30 seconds. Last mile pace, 8 minutes 30 seconds.",
            mile(number = 1, totalSec = 510, paceSec = 509.6),
        )
    }

    // --- finish ---------------------------------------------------------------

    @Test
    fun `the finish line reports distance time and average pace`() {
        val text = AnnouncementText.of(
            Announcement.Finished(
                distanceMeters = Units.milesToMeters(3.4),
                movingDurationSec = 31 * 60 + 12,
                avgPaceSecPerMile = 9 * 60 + 11.0,
            ),
        )

        assertEquals(
            "Run complete. Total distance 3.4 miles, time 31 minutes 12 seconds, " +
                "average pace 9 minutes 11 seconds per mile.",
            text,
        )
    }

    @Test
    fun `a run stopped before it moved says nothing about pace`() {
        val text = AnnouncementText.of(
            Announcement.Finished(
                distanceMeters = 0.0,
                movingDurationSec = 4,
                avgPaceSecPerMile = Double.NaN,
            ),
        )

        assertEquals("Run complete. Total distance 0 miles, time 4 seconds.", text)
    }

    @Test
    fun `a distance of exactly one mile is singular at the finish too`() {
        val text = AnnouncementText.of(
            Announcement.Finished(
                distanceMeters = Units.METERS_PER_MILE,
                movingDurationSec = 540,
                avgPaceSecPerMile = 540.0,
            ),
        )

        assertEquals(
            "Run complete. Total distance 1 mile, time 9 minutes, " +
                "average pace 9 minutes per mile.",
            text,
        )
    }

    // --- short cues -----------------------------------------------------------

    @Test
    fun `the countdown counts down and then says go`() {
        assertEquals("3.", AnnouncementText.of(Announcement.CountdownCue(3)))
        assertEquals("1.", AnnouncementText.of(Announcement.CountdownCue(1)))
        assertEquals("Go.", AnnouncementText.of(Announcement.Started))
    }

    @Test
    fun `pause and resume are acknowledged`() {
        assertEquals("Paused.", AnnouncementText.of(Announcement.Paused))
        assertEquals("Resumed.", AnnouncementText.of(Announcement.Resumed))
    }

    @Test
    fun `countdown cues jump the queue so they are not spoken late`() {
        assertEquals(Announcement.Priority.IMMEDIATE, Announcement.CountdownCue(3).priority)
        assertEquals(Announcement.Priority.IMMEDIATE, Announcement.Started.priority)
        assertEquals(Announcement.Priority.NORMAL, Announcement.Paused.priority)
    }
}
