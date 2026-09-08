package com.myrunningapp.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.myrunningapp.R

/**
 * The Android-facing half of the permission flow. The *decisions* live in
 * [com.myrunningapp.domain.permission.PermissionGate], which is pure and tested;
 * this file only reads the system's answers and draws the dialogs.
 */

/** What the system currently grants, read fresh — permissions change outside the app. */
fun Context.locationGrants(): SystemGrants = SystemGrants(
    fineLocationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION),
    // Before API 29 there is no separate background permission: foreground
    // location covers the screen-off case, so nothing is missing.
    backgroundLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
        true
    },
    notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        granted(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        true
    },
)

data class SystemGrants(
    val fineLocationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val notificationsGranted: Boolean,
)

private fun Context.granted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Asked together, in one dialog: both location permissions are the same question
 * to the user, and notifications ride along so the run's live stats have
 * somewhere to appear. Notifications never gate the run.
 */
fun foregroundPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

/**
 * From Android 11 the "Allow all the time" choice exists only in Settings — a
 * runtime request for it is refused without ever showing the user anything. On
 * Android 10 the ordinary dialog still works, so ask there rather than sending
 * the user out of the app for no reason.
 */
fun backgroundLocationNeedsSettings(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

fun appSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

/** Why the app is about to ask for location, before Android's own dialog. */
@Composable
fun ForegroundRationaleDialog(onAllow: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.permission_location_title)) },
        text = { Text(stringResource(R.string.permission_location_message)) },
        confirmButton = {
            TextButton(onClick = onAllow) { Text(stringResource(R.string.permission_allow)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * The separate, optional background ask. Declining is a real choice — it costs
 * screen-off tracking, not the run — so the dismiss button starts the run
 * rather than cancelling it.
 */
@Composable
fun BackgroundRationaleDialog(
    needsSettings: Boolean,
    onAllow: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.permission_background_title)) },
        text = {
            Text(
                stringResource(R.string.permission_background_message) +
                    if (needsSettings) {
                        "\n\n" + stringResource(R.string.permission_background_settings)
                    } else {
                        ""
                    },
            )
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(
                    stringResource(
                        if (needsSettings) {
                            R.string.permission_open_settings
                        } else {
                            R.string.permission_allow
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.permission_not_now)) }
        },
    )
}
