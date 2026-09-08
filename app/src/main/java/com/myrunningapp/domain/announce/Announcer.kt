package com.myrunningapp.domain.announce

/**
 * Speaks announcements out loud.
 *
 * The seam between the run and Android's speech engine: [com.myrunningapp.data.location.RunTracker]
 * decides *what* is worth saying, an implementation decides how it is said. Kept
 * as an interface so the tracker's rules — a mile speaks, a partial final mile
 * does not — are testable against a recording fake.
 */
interface Announcer {

    /** Says the line, or does nothing if the voice is muted. */
    fun announce(announcement: Announcement)

    /** Drops anything queued but not yet spoken — a cancelled countdown. */
    fun stop()

    /** An announcer that says nothing, for previews and tests. */
    object Silent : Announcer {
        override fun announce(announcement: Announcement) = Unit
        override fun stop() = Unit
    }
}
