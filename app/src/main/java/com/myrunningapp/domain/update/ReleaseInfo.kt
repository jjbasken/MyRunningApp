package com.myrunningapp.domain.update

/** A published release that carries an installable APK. */
data class ReleaseInfo(
    val version: AppVersion,
    val tag: String,
    val apkUrl: String,
    val sizeBytes: Long,
    /** Lower-case hex SHA-256 of the APK, as GitHub reports it for the asset. */
    val sha256: String,
    val pageUrl: String?,
)

/** What a check found, relative to the installed version. */
sealed interface UpdateCheckResult {
    data class Available(val release: ReleaseInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Failed(val reason: String) : UpdateCheckResult

    companion object {
        /** Only a strictly newer release is offered — never a downgrade or a reinstall. */
        fun compare(installed: AppVersion?, latest: ReleaseInfo?): UpdateCheckResult = when {
            latest == null -> Failed("The latest release has no usable APK")
            installed == null -> Failed("Cannot read the installed version")
            latest.version > installed -> Available(latest)
            else -> UpToDate
        }
    }
}
