package com.myrunningapp.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun `parses tags and version names alike`() {
        assertEquals(AppVersion(0, 3, 0), AppVersion.parse("v0.3.0"))
        assertEquals(AppVersion(0, 3, 0), AppVersion.parse("0.3.0"))
        assertEquals(AppVersion(1, 2, 0), AppVersion.parse("1.2"))
    }

    @Test
    fun `ignores build suffixes`() {
        assertEquals(AppVersion(0, 3, 0), AppVersion.parse("0.3.0-debug"))
        assertEquals(AppVersion(0, 3, 0), AppVersion.parse("v0.3.0-rc1"))
    }

    @Test
    fun `rejects things that are not versions`() {
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("v1"))
        assertNull(AppVersion.parse("1.2.3.4"))
    }

    @Test
    fun `orders numerically, not as text`() {
        assertTrue(AppVersion.parse("0.10.0")!! > AppVersion.parse("0.9.9")!!)
        assertTrue(AppVersion.parse("1.0.0")!! > AppVersion.parse("0.99.99")!!)
        assertTrue(AppVersion.parse("0.2.2")!! < AppVersion.parse("0.3.0")!!)
    }
}
