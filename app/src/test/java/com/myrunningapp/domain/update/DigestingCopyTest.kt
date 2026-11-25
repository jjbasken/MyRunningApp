package com.myrunningapp.domain.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class DigestingCopyTest {

    private val payload = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    @Test
    fun `copies a file that matches`() {
        val out = ByteArrayOutputStream()
        var last = 0L
        DigestingCopy.copyVerified(ByteArrayInputStream(payload), out, payload.size.toLong(), sha.uppercase()) { last = it }
        assertArrayEquals(payload, out.toByteArray())
        assertEquals(payload.size.toLong(), last)
    }

    @Test(expected = DigestingCopy.VerificationException::class)
    fun `rejects a different file of the same size`() {
        val tampered = payload.copyOf().also { it[1234] = (it[1234] + 1).toByte() }
        DigestingCopy.copyVerified(ByteArrayInputStream(tampered), ByteArrayOutputStream(), payload.size.toLong(), sha)
    }

    @Test(expected = DigestingCopy.VerificationException::class)
    fun `rejects a truncated download`() {
        DigestingCopy.copyVerified(ByteArrayInputStream(payload, 0, 1000), ByteArrayOutputStream(), payload.size.toLong(), sha)
    }

    @Test(expected = DigestingCopy.VerificationException::class)
    fun `stops a download that runs long`() {
        DigestingCopy.copyVerified(ByteArrayInputStream(payload + payload), ByteArrayOutputStream(), payload.size.toLong(), sha)
    }
}
