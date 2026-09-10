package com.myrunningapp.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthPermissionsTest {

    @Test
    fun `sync asks for exactly the three write permissions it needs`() {
        assertEquals(3, HealthPermissions.WRITE.size)
        assertTrue(HealthPermissions.WRITE.all { it.contains("WRITE") })
    }

    @Test
    fun `the route is asked for on top of them, not instead of them`() {
        assertEquals(HealthPermissions.WRITE + HealthPermissions.ROUTE, HealthPermissions.ALL)
        assertEquals(4, HealthPermissions.ALL.size)
    }
}
