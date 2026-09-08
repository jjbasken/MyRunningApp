package com.myrunningapp.domain.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionGateTest {

    @Test
    fun `asks for foreground location first`() {
        assertEquals(
            PermissionStep.ForegroundRationale,
            PermissionGate.next(PermissionStatus()),
        )
    }

    @Test
    fun `foreground comes before background even when both are missing`() {
        val status = PermissionStatus(
            fineLocationGranted = false,
            backgroundLocationGranted = false,
        )
        assertEquals(PermissionStep.ForegroundRationale, PermissionGate.next(status))
    }

    @Test
    fun `asks for background once foreground is granted`() {
        val status = PermissionStatus(fineLocationGranted = true)
        assertEquals(PermissionStep.BackgroundRationale, PermissionGate.next(status))
    }

    @Test
    fun `does not ask for background a second time`() {
        val status = PermissionStatus(
            fineLocationGranted = true,
            backgroundLocationGranted = false,
            backgroundAlreadyAsked = true,
        )
        assertEquals(PermissionStep.Ready, PermissionGate.next(status))
    }

    @Test
    fun `ready once everything is granted`() {
        val status = PermissionStatus(
            fineLocationGranted = true,
            backgroundLocationGranted = true,
            notificationsGranted = true,
        )
        assertEquals(PermissionStep.Ready, PermissionGate.next(status))
    }

    @Test
    fun `denied notifications never block the run`() {
        val status = PermissionStatus(
            fineLocationGranted = true,
            backgroundLocationGranted = true,
            notificationsGranted = false,
        )
        assertEquals(PermissionStep.Ready, PermissionGate.next(status))
    }

    @Test
    fun `pre-API-29 callers report background as granted and are asked nothing extra`() {
        val status = PermissionStatus(
            fineLocationGranted = true,
            backgroundLocationGranted = true,
        )
        assertEquals(PermissionStep.Ready, PermissionGate.next(status))
    }

    @Test
    fun `warns when background is missing but the run can still happen`() {
        assertTrue(
            PermissionGate.showsBackgroundWarning(
                PermissionStatus(fineLocationGranted = true, backgroundAlreadyAsked = true),
            ),
        )
    }

    @Test
    fun `no warning before foreground location is granted`() {
        assertFalse(PermissionGate.showsBackgroundWarning(PermissionStatus()))
    }

    @Test
    fun `no warning once background is granted`() {
        assertFalse(
            PermissionGate.showsBackgroundWarning(
                PermissionStatus(fineLocationGranted = true, backgroundLocationGranted = true),
            ),
        )
    }

    @Test
    fun `no warning once dismissed`() {
        assertFalse(
            PermissionGate.showsBackgroundWarning(
                PermissionStatus(
                    fineLocationGranted = true,
                    backgroundAlreadyAsked = true,
                    backgroundWarningDismissed = true,
                ),
            ),
        )
    }
}
