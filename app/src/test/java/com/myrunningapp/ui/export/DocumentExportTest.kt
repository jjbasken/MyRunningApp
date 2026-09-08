package com.myrunningapp.ui.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class DocumentExportTest {
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val uri = mockk<Uri>()

    init {
        every { context.contentResolver } returns resolver
    }

    @Test
    fun `opening writing and closing the document happen off the caller thread`() = runTest {
        val caller = Thread.currentThread()
        var closed = false
        val stream = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray) {
                assertNotEquals(caller, Thread.currentThread())
                super.write(bytes)
            }
            override fun close() {
                assertNotEquals(caller, Thread.currentThread())
                closed = true
                super.close()
            }
        }
        every { resolver.openOutputStream(uri) } answers {
            assertNotEquals(caller, Thread.currentThread())
            stream
        }
        assertTrue(context.write(uri, "route data"))
        assertEquals("route data", stream.toString("UTF-8"))
        assertTrue(closed)
    }

    @Test
    fun `provider failure is reported and the stream is closed`() = runTest {
        var closed = false
        val stream = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray) { throw IOException("Volume full") }
            override fun close() { closed = true }
        }
        every { resolver.openOutputStream(uri) } returns stream
        assertFalse(context.write(uri, "route data"))
        assertTrue(closed)
    }

    @Test
    fun `unavailable destination is reported as failure`() = runTest {
        every { resolver.openOutputStream(uri) } returns null
        assertFalse(context.write(uri, "route data"))
    }
}
