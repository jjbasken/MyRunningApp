package com.myrunningapp.domain.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reads GitHub's `GET /repos/{owner}/{repo}/releases/latest` response.
 *
 * The rules are strict on purpose, because whatever passes them gets handed to
 * the package installer:
 *
 * - drafts and pre-releases are skipped (the endpoint already excludes them;
 *   this is belt and braces);
 * - the tag must be a version, and the asset must be the one the release
 *   workflow uploads, `MyRunningApp-<tag>.apk`;
 * - the download must come from this repository's own release downloads on
 *   github.com over HTTPS — not any URL a response happens to contain;
 * - the asset must carry a SHA-256 digest, which the download is checked
 *   against. Android separately refuses any APK not signed with the installed
 *   app's key, so the digest's job is catching a truncated or swapped file
 *   before the installer is involved.
 */
object ReleaseParser {

    const val REPO = "jjbasken/MyRunningApp"
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"

    private val json = Json { ignoreUnknownKeys = true }
    private val SHA256 = Regex("^sha256:([0-9a-fA-F]{64})$")

    /** Null when the response is not a release this app may install. */
    fun parse(body: String): ReleaseInfo? {
        val release = runCatching { json.decodeFromString(GitHubRelease.serializer(), body) }.getOrNull()
            ?: return null
        if (release.draft || release.prerelease) return null
        val version = AppVersion.parse(release.tagName) ?: return null

        val expectedName = "MyRunningApp-${release.tagName}.apk"
        val asset = release.assets.firstOrNull { it.name == expectedName } ?: return null
        if (!asset.browserDownloadUrl.startsWith("$DOWNLOAD_PREFIX${release.tagName}/")) return null
        val sha256 = asset.digest?.let { SHA256.matchEntire(it) }?.groupValues?.get(1) ?: return null
        if (asset.size <= 0) return null

        return ReleaseInfo(
            version = version,
            tag = release.tagName,
            apkUrl = asset.browserDownloadUrl,
            sizeBytes = asset.size,
            sha256 = sha256.lowercase(),
            pageUrl = release.htmlUrl,
        )
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        val size: Long = 0,
        val digest: String? = null,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
    )
}
