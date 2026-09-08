package com.myrunningapp.domain.tracking

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class GpsFilterTest {

    private val filter = GpsFilter()
    private val t0: Instant = Instant.parse("2026-09-08T10:00:00Z")

    private fun fix(
        atSeconds: Long,
        latitude: Double = 51.5,
        longitude: Double = -0.12,
        accuracy: Float = 8f,
    ) = GpsFix(
        timestamp = t0.plusSeconds(atSeconds),
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = 20.0,
        accuracyMeters = accuracy,
    )

    @Test
    fun `a clean first fix is accepted`() {
        val f = fix(0)
        assertEquals(FixVerdict.ACCEPTED, filter.evaluate(f, previous = null, now = f.timestamp))
    }

    @Test
    fun `a fix less accurate than 25 metres is rejected`() {
        val f = fix(0, accuracy = 25.1f)
        assertEquals(FixVerdict.POOR_ACCURACY, filter.evaluate(f, previous = null, now = f.timestamp))
    }

    @Test
    fun `a fix accurate to exactly 25 metres is still accepted`() {
        val f = fix(0, accuracy = 25f)
        assertEquals(FixVerdict.ACCEPTED, filter.evaluate(f, previous = null, now = f.timestamp))
    }

    @Test
    fun `a fix older than 5 seconds is rejected as stale`() {
        val f = fix(0)
        val verdict = filter.evaluate(f, previous = null, now = t0.plusSeconds(6))
        assertEquals(FixVerdict.STALE, verdict)
    }

    @Test
    fun `a fix 5 seconds old is still fresh enough`() {
        val f = fix(0)
        val verdict = filter.evaluate(f, previous = null, now = t0.plusSeconds(5))
        assertEquals(FixVerdict.ACCEPTED, verdict)
    }

    @Test
    fun `a jump implying more than 13 metres per second is rejected`() {
        val previous = fix(0)
        // ~0.005 degrees of latitude in one second is roughly 555 m/s.
        val jump = fix(1, latitude = 51.505)
        assertEquals(
            FixVerdict.IMPLAUSIBLE_SPEED,
            filter.evaluate(jump, previous = previous, now = jump.timestamp),
        )
    }

    @Test
    fun `a realistic running step is accepted`() {
        val previous = fix(0)
        // ~0.00003 degrees of latitude per second is about 3.3 m/s — 8 min per mile.
        val next = fix(1, latitude = 51.50003)
        assertEquals(
            FixVerdict.ACCEPTED,
            filter.evaluate(next, previous = previous, now = next.timestamp),
        )
    }

    @Test
    fun `a fix that does not advance the clock is rejected as out of order`() {
        val previous = fix(10)
        val repeat = fix(10, latitude = 51.50001)
        assertEquals(
            FixVerdict.OUT_OF_ORDER,
            filter.evaluate(repeat, previous = previous, now = repeat.timestamp),
        )
    }

    @Test
    fun `accuracy is checked before the speed jump`() {
        val previous = fix(0)
        val bad = fix(1, latitude = 51.505, accuracy = 90f)
        assertEquals(
            FixVerdict.POOR_ACCURACY,
            filter.evaluate(bad, previous = previous, now = bad.timestamp),
        )
    }
}
