package com.myrunningapp.ui.export

import android.content.Context
import android.net.Uri
import com.myrunningapp.data.export.GpxImportException
import com.myrunningapp.data.export.GpxImportFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * What the system "open document" picker should offer for a GPX import:
 * everything.
 *
 * GPX has a registered type, but providers label a `.gpx` file however they
 * like — generic XML, plain bytes, plain text — and a file the picker greys out
 * cannot be chosen at all. The reader says plainly when a file is not GPX, which
 * beats a file the user can see but not tap.
 */
val GPX_IMPORT_MIME_TYPES = arrayOf("*/*")

/**
 * A long run at one fix a second is a few megabytes of GPX; this leaves room for
 * an ultra with every extension a watch can write, and still stops well short
 * of exhausting memory on a file that was never an activity.
 */
const val MAX_IMPORT_BYTES = 32L * 1024 * 1024

/**
 * Reads the whole of a picked document. Left as bytes: the XML parser works out
 * the encoding from the file's own declaration, which a guess here could not.
 *
 * @throws GpxImportException [GpxImportFailure.TOO_LARGE] past [maxBytes], or
 *   [GpxImportFailure.UNREADABLE] when the provider will not hand the bytes over.
 */
internal suspend fun Context.readBytes(uri: Uri, maxBytes: Long = MAX_IMPORT_BYTES): ByteArray =
    withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw GpxImportException(GpxImportFailure.TOO_LARGE)
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            // The grant can lapse between the picker returning and the read.
            null
        } ?: throw GpxImportException(GpxImportFailure.UNREADABLE)
    }
