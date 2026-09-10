package com.myrunningapp.domain.model

/** Where a run stands with Health Connect. */
enum class HealthSyncState {
    /** Never queued. Every run starts here, and stays here while the feature is off. */
    NOT_SYNCED,

    /** Waiting to be written. Set on finish, on an edit, and by the backfill. */
    PENDING,

    /** Written. A later edit puts it back to [PENDING]. */
    SYNCED,

    /**
     * Rejected for a reason retrying will not fix. Surfaced on the settings
     * screen; only "Sync now" moves it back to [PENDING]. Missing permission is
     * *not* this — that leaves the row pending.
     */
    FAILED,
}
