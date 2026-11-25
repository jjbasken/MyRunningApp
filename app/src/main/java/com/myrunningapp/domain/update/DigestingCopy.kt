package com.myrunningapp.domain.update

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Copies a download to disk while hashing it, and refuses anything that is not
 * exactly the expected file. Kept free of Android and networking so the checks
 * that stand between the internet and the package installer are unit-tested.
 */
object DigestingCopy {

    class VerificationException(message: String) : IOException(message)

    /**
     * Copies [input] to [output], reporting bytes written to [onProgress].
     *
     * @throws VerificationException if the stream runs past [expectedSize], ends
     *   short of it, or hashes to anything but [expectedSha256].
     */
    fun copyVerified(
        input: InputStream,
        output: OutputStream,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (Long) -> Unit = {},
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > expectedSize) throw VerificationException("Download is larger than the release says")
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            onProgress(total)
        }
        if (total != expectedSize) throw VerificationException("Download ended early ($total of $expectedSize bytes)")
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            throw VerificationException("Download does not match the release checksum")
        }
    }
}
