package com.myrunningapp.data.update

import android.content.pm.PackageInstaller
import com.myrunningapp.domain.update.AppVersion
import com.myrunningapp.domain.update.ReleaseInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateStateTest {

    private val release = ReleaseInfo(
        version = AppVersion(0, 4, 0),
        tag = "v0.4.0",
        apkUrl = "https://github.com/jjbasken/MyRunningApp/releases/download/v0.4.0/MyRunningApp-v0.4.0.apk",
        sizeBytes = 1,
        sha256 = "0".repeat(64),
        pageUrl = null,
    )

    @Test
    fun `backing out of the dialog offers the same update again`() {
        assertEquals(
            UpdateState.Available(release),
            UpdateState.afterInstall(PackageInstaller.STATUS_FAILURE_ABORTED, null, release),
        )
    }

    @Test
    fun `an abort after process death goes back to idle`() {
        assertEquals(UpdateState.Idle, UpdateState.afterInstall(PackageInstaller.STATUS_FAILURE_ABORTED, null, null))
    }

    @Test
    fun `a signing mismatch says so and keeps the release for a manual install`() {
        val state = UpdateState.afterInstall(PackageInstaller.STATUS_FAILURE_CONFLICT, "x", release)
        assertTrue(state is UpdateState.Failed && "signed differently" in state.message && state.release == release)
    }

    @Test
    fun `other failures surface the installer's message`() {
        assertEquals(
            UpdateState.Failed("boom", release),
            UpdateState.afterInstall(PackageInstaller.STATUS_FAILURE, "boom", release),
        )
    }

    @Test
    fun `success means up to date`() {
        assertEquals(UpdateState.UpToDate, UpdateState.afterInstall(PackageInstaller.STATUS_SUCCESS, null, release))
    }

    @Test
    fun `only states tied to a release can be installed from`() {
        assertEquals(release, UpdateState.Installing(release).installableRelease)
        assertEquals(release, UpdateState.Failed("x", release).installableRelease)
        assertNull(UpdateState.Downloading(release, 0.5f).installableRelease)
        assertNull(UpdateState.UpToDate.installableRelease)
        assertNull(UpdateState.Failed("x", null).installableRelease)
    }
}
