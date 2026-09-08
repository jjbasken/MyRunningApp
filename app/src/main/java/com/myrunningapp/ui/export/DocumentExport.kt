package com.myrunningapp.ui.export

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import com.myrunningapp.data.export.ExportDocument

/**
 * Drives the Storage Access Framework for one pending [ExportDocument]: opens
 * the system "create document" picker as soon as one appears, then writes the
 * text to whatever the user chose.
 *
 * Shared by the run detail and profile screens so the picker/write/report dance
 * exists once. The app never asks for storage permissions — SAF grants access to
 * the single file the user picked, and nothing else.
 *
 * @param mimeType fixed per call site, because the picker's contract is built
 *   once and cannot change type between launches.
 * @param onFinished true once the bytes are written, false if the write failed;
 *   not called at all when the user backs out of the picker.
 */
@Composable
fun DocumentExportEffect(
    document: ExportDocument?,
    mimeType: String,
    onCancelled: () -> Unit,
    onFinished: (Boolean) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    // The callback fires long after this composition; read the document then,
    // not now, or a re-composition would write a stale file.
    val pending by rememberUpdatedState(document)
    val finished by rememberUpdatedState(onFinished)
    val cancelled by rememberUpdatedState(onCancelled)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mimeType),
    ) { uri ->
        val content = pending
        when {
            uri == null -> cancelled()
            content == null -> cancelled()
            else -> scope.launch { finished(context.write(uri, content.content)) }
        }
    }

    LaunchedEffect(document) {
        document?.let { launcher.launch(it.fileName) }
    }
}

/**
 * @return whether the write succeeded. A picked `Uri` can still fail — a full
 *   volume, or a provider that went away — and silently losing an export the
 *   user asked for is worse than saying so.
 */
internal suspend fun Context.write(uri: Uri, content: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(content.toByteArray())
        } ?: return@runCatching false
        true
    }.getOrDefault(false)
}
