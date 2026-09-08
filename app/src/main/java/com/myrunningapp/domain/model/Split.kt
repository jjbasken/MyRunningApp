package com.myrunningapp.domain.model

/**
 * One mile of a run, finalised at the moment the mile completes (the same
 * instant its announcement is spoken).
 *
 * The final split of a run is usually a partial mile: [distanceMeters] is then
 * less than a mile and [paceSecPerMile] is projected from the partial distance.
 */
data class Split(
    val id: Long,
    val runId: Long,
    /** 1 for the first mile, 2 for the second, and so on. */
    val splitNumber: Int,
    val distanceMeters: Double,
    val durationSec: Long,
    val paceSecPerMile: Double,
)
