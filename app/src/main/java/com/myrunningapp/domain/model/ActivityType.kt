package com.myrunningapp.domain.model

/** The kinds of activity the app tracks. Chosen before a run starts. */
enum class ActivityType {
    RUN,
    WALK,
    BIKE,
    ;

    /**
     * Whether the activity is naturally read as speed (mph) rather than as pace
     * (minutes per mile). Cyclists think in miles per hour; runners and walkers
     * do not.
     */
    val readsAsSpeed: Boolean
        get() = this == BIKE
}
