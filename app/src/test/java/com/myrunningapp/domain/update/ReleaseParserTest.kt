package com.myrunningapp.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {

    private val sha = "c6eb4a5e9d1ebe9c0cd35db6c200b2043a87c105a21a96ee1730d6bf76a17b10"

    private fun release(
        tag: String = "v0.4.0",
        assetName: String = "MyRunningApp-$tag.apk",
        url: String = "https://github.com/jjbasken/MyRunningApp/releases/download/$tag/$assetName",
        digest: String? = "sha256:$sha",
        size: Long = 2_701_555,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ) = """
        {
          "tag_name": "$tag",
          "html_url": "https://github.com/jjbasken/MyRunningApp/releases/tag/$tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "body": "notes",
          "assets": [
            {"name": "something-else.txt", "size": 10, "browser_download_url": "https://github.com/jjbasken/MyRunningApp/releases/download/$tag/something-else.txt"},
            {"name": "$assetName", "size": $size, ${digest?.let { "\"digest\": \"$it\"," } ?: ""}
             "content_type": "application/vnd.android.package-archive",
             "browser_download_url": "$url"}
          ]
        }
    """.trimIndent()

    @Test
    fun `reads the real response shape`() {
        val info = ReleaseParser.parse(release())!!
        assertEquals(AppVersion(0, 4, 0), info.version)
        assertEquals("v0.4.0", info.tag)
        assertEquals(2_701_555L, info.sizeBytes)
        assertEquals(sha, info.sha256)
        assertTrue(info.apkUrl.endsWith("/v0.4.0/MyRunningApp-v0.4.0.apk"))
    }

    @Test
    fun `refuses drafts and pre-releases`() {
        assertNull(ReleaseParser.parse(release(draft = true)))
        assertNull(ReleaseParser.parse(release(prerelease = true)))
    }

    @Test
    fun `refuses a release without a checksum`() {
        assertNull(ReleaseParser.parse(release(digest = null)))
        assertNull(ReleaseParser.parse(release(digest = "md5:abc")))
    }

    @Test
    fun `refuses downloads from anywhere but this repo's releases`() {
        assertNull(ReleaseParser.parse(release(url = "https://evil.example/MyRunningApp-v0.4.0.apk")))
        assertNull(ReleaseParser.parse(release(url = "http://github.com/jjbasken/MyRunningApp/releases/download/v0.4.0/MyRunningApp-v0.4.0.apk")))
        assertNull(ReleaseParser.parse(release(url = "https://github.com/someone/MyRunningApp/releases/download/v0.4.0/MyRunningApp-v0.4.0.apk")))
        assertNull(ReleaseParser.parse(release(url = "https://github.com/jjbasken/MyRunningApp/releases/download/v0.1.0/MyRunningApp-v0.4.0.apk")))
    }

    @Test
    fun `refuses a release without the expected apk`() {
        assertNull(ReleaseParser.parse(release(assetName = "Other.apk")))
        assertNull(ReleaseParser.parse(release(size = 0)))
    }

    @Test
    fun `refuses non-version tags and malformed json`() {
        assertNull(ReleaseParser.parse(release(tag = "nightly")))
        assertNull(ReleaseParser.parse("{not json"))
        assertNull(ReleaseParser.parse("""{"message":"Not Found"}"""))
    }

    @Test
    fun `offers only strictly newer releases`() {
        val info = ReleaseParser.parse(release())!!
        assertEquals(UpdateCheckResult.Available(info), UpdateCheckResult.compare(AppVersion(0, 3, 0), info))
        assertEquals(UpdateCheckResult.UpToDate, UpdateCheckResult.compare(AppVersion(0, 4, 0), info))
        assertEquals(UpdateCheckResult.UpToDate, UpdateCheckResult.compare(AppVersion(0, 5, 0), info))
        assertTrue(UpdateCheckResult.compare(AppVersion(0, 3, 0), null) is UpdateCheckResult.Failed)
    }
}
