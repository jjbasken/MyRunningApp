package com.myrunningapp.ui.map

import com.myrunningapp.domain.Units
import com.myrunningapp.domain.tracking.Geo
import kotlin.math.roundToInt

/**
 * A stretch of route drawn in one colour, with the point that starts the next
 * band repeated as its last point so consecutive bands join without a gap.
 */
data class PaceBand(
    val points: List<RouteCoordinate>,
    val paceSecPerMile: Double,
    /** Packed 0xRRGGBB; the map turns it into an osmdroid paint colour. */
    val rgb: Int,
)

/**
 * Colours a finished route by how fast each stretch was run.
 *
 * Two decisions carry most of the weight here:
 *
 * **Chunking.** Per-fix pace at 1 Hz is mostly GPS noise — two metres of jitter
 * on a three-metre step is a wild pace. So the route is cut into roughly
 * [CHUNK_METERS] chunks and each chunk gets one pace. That smooths the noise and
 * keeps the overlay to a few dozen polylines rather than thousands.
 *
 * **Normalising.** The ramp is scaled to *this run's own* spread, using the 10th
 * and 90th percentile of chunk paces rather than the extremes, so one stop at a
 * traffic light does not wash the whole route into "fast". An easy run and a
 * tempo run each use the full ramp; the colours say fast-and-slow-for-this-run,
 * not fast-and-slow-in-general.
 *
 * Pure Kotlin — no Android colour types — so the ramp is unit-tested.
 */
object PaceColors {

    private const val CHUNK_METERS = 100.0

    /** Below this a run has no meaningful spread to colour. */
    private const val MIN_BANDS = 2

    private const val FAST_PERCENTILE = 0.10
    private const val SLOW_PERCENTILE = 0.90

    /** Green (fast) through amber to red (slow). */
    private const val FAST_RGB = 0x2E9B4F
    private const val MID_RGB = 0xE0A21B
    private const val SLOW_RGB = 0xC7392C

    /**
     * @return one band per coloured stretch, or an empty list when the route is
     *   too short, or has no timestamps, to say anything about pace.
     */
    fun bands(points: List<RouteCoordinate>): List<PaceBand> {
        val chunks = chunk(points)
        if (chunks.size < MIN_BANDS) return emptyList()

        val paces = chunks.map { it.paceSecPerMile }.sorted()
        val fast = percentile(paces, FAST_PERCENTILE)
        val slow = percentile(paces, SLOW_PERCENTILE)
        return chunks.map { chunk ->
            PaceBand(
                points = chunk.points,
                paceSecPerMile = chunk.paceSecPerMile,
                rgb = colorFor(chunk.paceSecPerMile, fast, slow),
            )
        }
    }

    /**
     * Interpolates the ramp. [fast] and [slow] are paces in seconds per mile, so
     * *lower* is faster; anything outside the range clamps to an end colour.
     */
    fun colorFor(paceSecPerMile: Double, fast: Double, slow: Double): Int {
        if (!paceSecPerMile.isFinite()) return MID_RGB
        // A run held at one pace has no spread to divide by; call it all middle.
        if (slow - fast < 1.0) return MID_RGB
        val t = ((paceSecPerMile - fast) / (slow - fast)).coerceIn(0.0, 1.0)
        return if (t < 0.5) {
            blend(FAST_RGB, MID_RGB, t * 2)
        } else {
            blend(MID_RGB, SLOW_RGB, (t - 0.5) * 2)
        }
    }

    /** The ramp's stops, fast first — the legend draws a gradient through these. */
    fun rampStops(): List<Int> = listOf(FAST_RGB, MID_RGB, SLOW_RGB)

    private class Chunk(val points: List<RouteCoordinate>, val paceSecPerMile: Double)

    /**
     * Walks the route accumulating distance and time, closing a chunk each time
     * it passes [CHUNK_METERS]. Each chunk starts on the point the previous one
     * ended on, so the drawn bands meet rather than leaving a hairline gap.
     *
     * Legs that bridge a pause are skipped: no distance was covered standing
     * still, and the clock gap would read as a crawl.
     */
    private fun chunk(points: List<RouteCoordinate>): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var current = mutableListOf<RouteCoordinate>()
        var meters = 0.0
        var seconds = 0.0

        fun close(startNextAt: RouteCoordinate?) {
            if (meters > 0.0 && seconds > 0.0 && current.size >= 2) {
                chunks.add(
                    Chunk(
                        points = current.toList(),
                        paceSecPerMile = Units.paceSecPerMile(
                            meters,
                            seconds.roundToInt().toLong(),
                        ),
                    ),
                )
            }
            current = startNextAt?.let { mutableListOf(it) } ?: mutableListOf()
            meters = 0.0
            seconds = 0.0
        }

        points.zipWithNext().forEach { (a, b) ->
            val fromTime = a.timestampMillis
            val toTime = b.timestampMillis
            if (a.segmentIndex != b.segmentIndex || fromTime == null || toTime == null) {
                // The track breaks here; whatever has accumulated stands alone
                // and the next chunk starts fresh on the far side of the gap.
                close(startNextAt = null)
                return@forEach
            }
            if (current.isEmpty()) current.add(a)
            meters += Geo.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            seconds += (toTime - fromTime) / 1000.0
            current.add(b)
            if (meters >= CHUNK_METERS) close(startNextAt = b)
        }
        // The last stretch is usually short of a full chunk but still ran.
        close(startNextAt = null)
        return chunks
    }

    /** Nearest-rank on an already-sorted list. */
    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val index = ((sorted.size - 1) * fraction).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun blend(from: Int, to: Int, t: Double): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).roundToInt().coerceIn(0, 255)
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
