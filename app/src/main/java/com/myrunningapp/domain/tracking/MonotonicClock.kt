package com.myrunningapp.domain.tracking

/** Milliseconds since boot, including deep sleep; independent of wall-clock corrections. */
fun interface MonotonicClock {
    fun elapsedRealtimeMillis(): Long
}
