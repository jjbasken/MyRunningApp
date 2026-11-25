package com.myrunningapp.domain.update

/**
 * A `major.minor.patch` release version, as written in `versionName` and in
 * release tags (`v0.3.0`).
 *
 * Anything after the numbers — the debug build's `-debug` suffix, a `-rc1` — is
 * dropped rather than ordered: the release workflow only ever publishes plain
 * versions, and the updater only ever compares against those.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^v?(\d{1,6})\.(\d{1,6})(?:\.(\d{1,6}))?(?:[-+].*)?$""")

        /** Null for anything that is not a version, so a stray tag is ignored, not fatal. */
        fun parse(text: String): AppVersion? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch) = match.destructured
            return AppVersion(major.toInt(), minor.toInt(), patch.ifEmpty { "0" }.toInt())
        }
    }
}
