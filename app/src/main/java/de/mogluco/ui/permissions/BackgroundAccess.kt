/*      This file is part of Mogluco, a Material 3 redesign fork of                  */
/*      Juggluco, for Freestyle Libre 2 and 3 glucose sensors.                       */
/*                                                                                   */
/*      Copyright (C) 2026 Joshua Meyer                                              */
/*                                                                                   */
/*      Mogluco is free software: you can redistribute it and/or modify              */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Mogluco is distributed in the hope that it will be useful, but               */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Mogluco. If not, see <https://www.gnu.org/licenses/>.             */
/*                                                                                   */
package de.mogluco.ui.permissions

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Whether Mogluco may post notifications: the permission (Android 13+), and the app's
 * notifications not switched off in the system settings. Alarms ring without them, but the
 * current value in the status bar and Dismiss / Snooze on the lock screen need them.
 */
fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/**
 * Battery usage set to "Restricted": the system stops the app in the background, and ignores
 * its monitoring service's request to stay in the foreground — readings and alarms then end
 * soon after Mogluco leaves the screen.
 */
fun backgroundRestricted(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

/** Battery usage "Unrestricted": not restricted, and exempt from battery optimisation (Doze). */
fun backgroundUnrestricted(context: Context): Boolean =
    !backgroundRestricted(context) &&
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/** The system's notification settings for Mogluco. */
fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
}

/**
 * Where battery usage is set to "Unrestricted". From "Optimized" the system's own question does
 * it in one tap. From "Restricted" that question does not lift the restriction, so the app's info
 * page opens instead, where "App battery usage" holds the choice.
 */
fun openBackgroundSettings(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    if (backgroundRestricted(context)) {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)) }
        return
    }
    // Each where the one before is not there (some builds leave them out): the last always is.
    runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)) }
}

/**
 * Where, in the app's info page that [openBackgroundSettings] opens for "Restricted", the choice
 * is: its name has changed over the Android versions.
 */
fun restrictedHint(): String = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        "open “App battery usage”, turn on “Allow background usage” where it is shown, and choose “Unrestricted”"
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "choose “Battery” → “Unrestricted”"
    else -> "open “Advanced” → “Battery” → “Background restriction” and choose “Remove”"
}

/**
 * The action that asks for notifications: the system's own dialog where it can still show one,
 * otherwise Mogluco's notification settings. Only ever on a tap that asks for it — the user's
 * "Don't allow" in the system dialog is an answer, and nothing follows it.
 *
 * The system stops showing its dialog once the permission was turned down twice (or "for good").
 * That is told by its own rationale flag, which is off both then and before the first request —
 * so the app notes that it has asked. Where it has not (a denial from before this note existed),
 * an answer that comes back faster than anyone taps tells that the dialog was skipped.
 */
@Composable
fun rememberNotificationRequest(): () -> Unit {
    val context = LocalContext.current
    val prefs = (context.applicationContext as de.mogluco.MoglucoApp).preferences
    // When the request went out where a version before this note may have asked already (0 = not
    // so, or none is out): only then can the dialog have been skipped unknown to the app.
    val unnotedRequestAt = remember { mutableLongStateOf(0L) }
    // One request at a time: a second tap before the system's dialog is up would be answered at
    // once with "denied" — and taken for a skipped dialog.
    val inFlight = remember { mutableStateOf(false) }
    // The system's rationale flag as it was when the request went out (see the result below).
    val rationaleBefore = remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        inFlight.value = false
        // Noted only where the system took an answer: granted, or a "Don't allow" — after which
        // the rationale flag is on (the first time), or was on before (the second, final time). A
        // dialog closed without an answer (back, or finished from outside) leaves both off; the
        // system will still ask then, and so the next tap must offer its dialog again.
        val rationaleNow = context.findActivity()?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS)
        } == true
        if (granted || rationaleBefore.value || rationaleNow) prefs.setNotificationsRequested(true)
        val since = unnotedRequestAt.longValue
        unnotedRequestAt.longValue = 0L
        if (!granted && since != 0L && SystemClock.elapsedRealtime() - since < SYSTEM_DIALOG_SKIPPED_MS) {
            openNotificationSettings(context)
        }
    }
    return remember(context, launcher) {
        {
            val permissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            val rationale = context.findActivity()?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS)
            } == true
            val systemWouldAsk = !prefs.notificationsRequested.value || rationale
            if (permissionMissing && systemWouldAsk) {
                if (!inFlight.value) {
                    inFlight.value = true
                    rationaleBefore.value = rationale
                    unnotedRequestAt.longValue =
                        if (prefs.notificationsRequested.value || !updatedInstall(context)) 0L
                        else SystemClock.elapsedRealtime()
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                // Turned down for good, or granted but switched off in the system settings (or
                // before Android 13): only the settings can change it.
                openNotificationSettings(context)
            }
        }
    }
}

/** Faster than anyone answers a dialog: the system's permission dialog was not shown. */
private const val SYSTEM_DIALOG_SKIPPED_MS = 1_000L

/** Installed as an update of an earlier version (which may have asked for notifications itself). */
private fun updatedInstall(context: Context): Boolean = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.lastUpdateTime != info.firstInstallTime
}.getOrDefault(true)

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The one-time question on first open: notifications, and running in the background. Each
 * missing one has its own button; [onDone] closes it — "Not now", back or a tap outside count as
 * an answer, and it is not asked again by itself (the Notifications settings still offer both).
 */
@Composable
fun BackgroundAccessDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(notificationsAllowed(context)) }
    var background by remember { mutableStateOf(backgroundUnrestricted(context)) }
    var restricted by remember { mutableStateOf(backgroundRestricted(context)) }
    // Coming back from the system's dialog or settings resumes the activity, not this dialog.
    LifecycleResumeEffect(Unit) {
        notifications = notificationsAllowed(context)
        background = backgroundUnrestricted(context)
        restricted = backgroundRestricted(context)
        onPauseOrDispose { }
    }
    val requestNotifications = rememberNotificationRequest()
    val allSet = notifications && background
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Let Mogluco keep watch") },
        text = {
            // Scrolls where it does not fit (landscape, large fonts): the second item must not be
            // cut off below the first.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(
                    "For readings and alarms to reach you when Mogluco is not on screen, it needs two things.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                AccessRow(
                    icon = Icons.Filled.Notifications,
                    title = "Notifications",
                    body = "Your current glucose in the status bar, and Dismiss / Snooze for alarms on the lock screen.",
                    allowed = notifications,
                    onAllow = requestNotifications,
                )
                AccessRow(
                    icon = Icons.Filled.BatteryFull,
                    title = "Run in the background",
                    body = if (restricted) {
                        "Battery usage is set to “Restricted”, so readings and alarms stop soon after you leave the app. " +
                            "In the app info that opens, ${restrictedHint()}."
                    } else {
                        "Set battery usage to “Unrestricted”, so the system does not pause readings and alarms."
                    },
                    allowed = background,
                    onAllow = { openBackgroundSettings(context) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDone) { Text(if (allSet) "Done" else "Not now") }
        },
    )
}

@Composable
private fun AccessRow(
    icon: ImageVector,
    title: String,
    body: String,
    allowed: Boolean,
    onAllow: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (allowed) {
                Text("Allowed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            } else {
                FilledTonalButton(onClick = onAllow) { Text("Allow") }
            }
        }
    }
}
