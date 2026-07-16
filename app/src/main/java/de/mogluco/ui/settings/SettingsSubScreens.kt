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
package de.mogluco.ui.settings

import android.media.RingtoneManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.mogluco.aod.AodOverlayService
import de.mogluco.data.AodPosition
import de.mogluco.data.AppPreferences
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.LogKind
import de.mogluco.data.SensorInfo
import de.mogluco.data.VibrationPattern
import de.mogluco.ui.util.autofillEmail
import de.mogluco.ui.util.autofillPassword
import de.mogluco.ui.util.copyToClipboard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ---------------- Sensor ----------------

@Composable
fun SensorSettingsScreen(viewModel: SettingsViewModel, onActivate: () -> Unit, onBack: () -> Unit) {
    val prefs = viewModel.prefs
    val context = LocalContext.current
    val sensor by viewModel.sensor.collectAsStateWithLifecycle()
    val unit by prefs.unit.collectAsStateWithLifecycle()
    val accountId by prefs.accountId.collectAsStateWithLifecycle()
    var account by remember(accountId) { mutableStateOf(accountId) }

    val engineLive = tk.glucodata.Engine.isStarted()
    val scope = rememberCoroutineScope()
    var libreEmail by remember { mutableStateOf(if (engineLive) tk.glucodata.Engine.libreviewEmail() else "") }
    var librePassword by remember { mutableStateOf("") }
    var libreStatus by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    var uploadEnabled by remember { mutableStateOf(engineLive && tk.glucodata.Engine.isUploadEnabled()) }

    // The LibreView account-ID fetch is asynchronous (the native thread can take a while). If a
    // previous fetch completed after we stopped polling, pick it up when the screen reopens.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (engineLive && account.isBlank()) {
            val id = tk.glucodata.Engine.libreAccountId()
            if (id.isNotBlank()) {
                val num = tk.glucodata.Engine.libreAccountIdNumber()
                account = if (num != 0L) num.toString() else id.filter { c -> c.isDigit() }
                prefs.setAccountId(account)
            }
        }
    }

    SettingsSubScreen("Sensor", onBack) {
        SettingsSection("Active sensor") {
            val s = sensor
            if (s != null) {
                val expired = s.expiryMillis() <= System.currentTimeMillis()
                // Each row copies only its own value when tapped.
                InfoRow("Sensor ID", s.id, trailingIcon = Icons.Filled.ContentCopy) {
                    copyToClipboard(context, "Sensor ID", s.id)
                }
                InfoRow("Activated", dateLabel(s.activationMillis)) {
                    copyToClipboard(context, "Activated", dateLabel(s.activationMillis))
                }
                InfoRow(if (expired) "Expired" else "Expires", dateLabel(s.expiryMillis())) {
                    copyToClipboard(context, if (expired) "Expired" else "Expires", dateLabel(s.expiryMillis()))
                }
                InfoRow("Day", "${s.dayOfLife()} of ${s.lifetimeDays}")
            } else {
                Text(
                    "No sensor active.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }


        SettingsSection("LibreView account") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = account,
                    // Persist as the user types — no separate save step.
                    onValueChange = { new -> account = new.filter { c -> c.isDigit() }; prefs.setAccountId(account) },
                    label = { Text("Account ID (required)") },
                    isError = account.isBlank(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (account.isBlank()) {
                    Text(
                        "Required before activating a sensor — without it the sensor rejects the scan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "To start a NEW sensor, enter any number here. To take over a sensor started by " +
                        "the Abbott Libre 3 app, use that account's LibreView ID — or fetch it below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                // --- Fetch account ID from LibreView (email + password) ---
                Text("Fetch from LibreView", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = libreEmail,
                    onValueChange = { libreEmail = it },
                    label = { Text("LibreView e-mail") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth().autofillEmail { libreEmail = it },
                )
                OutlinedTextField(
                    value = librePassword,
                    onValueChange = { librePassword = it },
                    label = { Text("LibreView password") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().autofillPassword { librePassword = it },
                )
                Button(
                    onClick = {
                        if (!engineLive) {
                            libreStatus = "Needs the live engine (sample-data mode is on)."
                            return@Button
                        }
                        fetching = true
                        libreStatus = "Contacting LibreView…"
                        tk.glucodata.Engine.setLibreviewCredentials(libreEmail.trim(), librePassword, 0)
                        tk.glucodata.Engine.fetchAccountId()
                        scope.launch {
                            // The native thread fetches asynchronously and a cold request (config +
                            // auth round-trips to Abbott's servers) can be slow, so poll patiently
                            // (~2 min). It keeps running even if you leave the screen — the on-open
                            // effect above also picks up a late result.
                            repeat(60) {
                                val id = tk.glucodata.Engine.libreAccountId()
                                if (id.isNotBlank()) {
                                    // The fetched ID is stored natively as a string; the number
                                    // actually sent to the sensor is the engine's derived value
                                    // (it hashes non-numeric IDs). Persist THAT so activation pushes
                                    // the same number — never the raw string (which may be a GUID).
                                    val num = tk.glucodata.Engine.libreAccountIdNumber()
                                    account = if (num != 0L) num.toString() else id.filter { c -> c.isDigit() }
                                    prefs.setAccountId(account)
                                    libreStatus = "Account ID received."
                                    fetching = false
                                    return@launch
                                }
                                libreStatus = tk.glucodata.Engine.libreviewStatus().ifBlank { "Contacting LibreView…" }
                                kotlinx.coroutines.delay(2000)
                            }
                            fetching = false
                            if (account.isBlank()) {
                                libreStatus = "Still no account ID — check your e-mail/password, or reopen this screen in a moment."
                            }
                        }
                    },
                    enabled = !fetching && libreEmail.isNotBlank() && librePassword.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (fetching) "Fetching…" else "Get Account ID") }
                if (libreStatus.isNotBlank()) {
                    Text(libreStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                // --- Upload collected data to LibreView ---
                SwitchRow(
                    null,
                    "Upload to LibreView automatically",
                    subtitle = "Keep sending new readings in the background",
                    checked = uploadEnabled,
                    onCheckedChange = {
                        uploadEnabled = it
                        if (engineLive) tk.glucodata.Engine.setUploadEnabled(it)
                    },
                )
                OutlinedButton(
                    onClick = {
                        if (engineLive) {
                            scope.launch {
                                libreStatus = "Pushing to LibreView…"
                                // One-shot push that does NOT flip the background-upload toggle.
                                tk.glucodata.Engine.pushOnce()
                                // Reflect the real upload status instead of a static "Pushing…":
                                // poll the engine for up to ~30 s.
                                repeat(15) {
                                    kotlinx.coroutines.delay(2000)
                                    val s = tk.glucodata.Engine.libreviewStatus()
                                    if (s.isNotBlank()) libreStatus = s
                                }
                            }
                        } else {
                            libreStatus = "Needs the live engine (sample-data mode is on)."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Push to LibreView now") }
                Text(
                    "“Push now” sends the collected readings once. It does not turn on background " +
                        "uploading — use the toggle above for that.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsSection("Sensor control") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onActivate, modifier = Modifier.fillMaxWidth()) {
                    Text(if (sensor == null) "Activate new sensor" else "Replace / activate sensor")
                }
                val s = sensor
                if (s != null) {
                    val pulling by SensorPullState.inProgress.collectAsStateWithLifecycle()
                    OutlinedButton(
                        onClick = { pullSensorHistory(context) },
                        enabled = !pulling,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (pulling) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("Pulling from sensor…")
                        } else {
                            Text("Pull history from sensor")
                        }
                    }
                    Text(
                        "Ask the sensor to replay its stored readings to fill gaps of readings that " +
                        "the app missed. Keep the phone near the sensor for a minute after.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Safety: clear the spinner if no completion arrives (e.g. the sensor dropped).
                    androidx.compose.runtime.LaunchedEffect(pulling) {
                        if (pulling) {
                            kotlinx.coroutines.delay(90_000)
                            SensorPullState.inProgress.value = false
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            // Deactivating clears the active-sensor state (card + reading) so the
                            // screen returns to "no sensor / add sensor" — communication is gone by
                            // then anyway, so keeping it shown was misleading.
                            if (viewModel.deactivateSensor(s.id)) {
                                android.widget.Toast.makeText(
                                    context,
                                    "Deactivated sensor ${s.id}.",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    "Deactivation needs the live engine (sample data mode is on).",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Deactivate sensor") }
                }
            }
        }
    }

}

/**
 * Shared state for the manual sensor-history pull: the button reflects [inProgress] (spinner +
 * disabled), and the app-wide deep-pull completion listener in MoglucoApp clears it (and toasts
 * the result). A [androidx.compose.runtime.LaunchedEffect] safety timeout also clears it if no
 * completion arrives.
 */
object SensorPullState {
    val inProgress = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/**
 * Trigger a deep history replay from the connected sensor (its last ~8h on-device buffer). Only
 * works on the device that currently holds the sensor's BLE connection; the mirror then carries the
 * recovered readings to the watch. The completion (record count) is toasted from MoglucoApp.
 */
private fun pullSensorHistory(context: android.content.Context) {
    if (!tk.glucodata.Engine.isStarted()) {
        android.widget.Toast.makeText(
            context,
            "Needs the live engine (sample-data mode is on).",
            android.widget.Toast.LENGTH_LONG,
        ).show()
        return
    }
    SensorPullState.inProgress.value = true
    // The sensor's on-device historic buffer is ~8h; request a little more so we get all of it.
    val n = tk.glucodata.Sensors.pullHistoryFromSensor(10 * 60)
    if (n <= 0) {
        SensorPullState.inProgress.value = false
        android.widget.Toast.makeText(
            context,
            "No sensor connected to this phone right now — make sure it's connected, then try again.",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }
}

// ---------------- Event logging ----------------

@Composable
fun EventLoggingScreen(prefs: AppPreferences, onBack: () -> Unit) {
    val enabled by prefs.enabledLogKinds.collectAsStateWithLifecycle()
    SettingsSubScreen("Event logging", onBack) {
        SettingsSection("What to log") {
            Text(
                "Each option controls whether it appears in the + button, on the graph and in the legend.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            )
            LogKind.entries.forEach { kind ->
                SwitchRow(
                    icon = null,
                    title = kind.label,
                    checked = kind in enabled,
                    onCheckedChange = { prefs.setLogKindEnabled(kind, it) },
                )
            }
        }
    }
}

// ---------------- Target range & alarms ----------------

@Composable
fun TargetAlarmsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val prefs = viewModel.prefs
    val context = LocalContext.current
    val unit by prefs.unit.collectAsStateWithLifecycle()
    val low by prefs.targetLow.collectAsStateWithLifecycle()
    val high by prefs.targetHigh.collectAsStateWithLifecycle()
    val band by prefs.showTargetBand.collectAsStateWithLifecycle()
    val lowAlarm by prefs.lowAlarmEnabled.collectAsStateWithLifecycle()
    val highAlarm by prefs.highAlarmEnabled.collectAsStateWithLifecycle()
    val loss by prefs.signalLossAlarm.collectAsStateWithLifecycle()
    val signalLossMin by prefs.signalLossMinutes.collectAsStateWithLifecycle()
    val overrideDnd by prefs.alarmOverrideDnd.collectAsStateWithLifecycle()
    val repeat by prefs.alarmRepeatMinutes.collectAsStateWithLifecycle()
    val reminderRepeat by prefs.reminderRepeatMinutes.collectAsStateWithLifecycle()
    val timeout by prefs.alarmTimeoutSeconds.collectAsStateWithLifecycle()
    val vibrate by prefs.alarmVibrate.collectAsStateWithLifecycle()
    val pattern by prefs.vibrationPattern.collectAsStateWithLifecycle()
    val sound by prefs.alarmSound.collectAsStateWithLifecycle()
    val soundTitle by prefs.alarmSoundTitle.collectAsStateWithLifecycle()
    val soundUri by prefs.alarmSoundUri.collectAsStateWithLifecycle()

    var showRangeDialog by remember { mutableStateOf(false) }
    var editRepeat by remember { mutableStateOf<String?>(null) } // "alarm" | "reminder"

    // API 34+ defaults full-screen intents to DENIED for sideloaded/Play apps; without the grant
    // the alarm screen silently never appears over the lock screen. Re-read on every resume so
    // returning from the system settings page refreshes the row.
    var canFullScreenAlarm by remember { mutableStateOf(canUseFullScreenIntent(context)) }
    var dndAccessGranted by remember { mutableStateOf(hasDndAccess(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        canFullScreenAlarm = canUseFullScreenIntent(context)
        val granted = hasDndAccess(context)
        if (granted && !dndAccessGranted) {
            // Back from the DND-access settings with the grant: re-stamp the bypass flag onto
            // the alarm channels now — the system only accepts it while access is held.
            de.mogluco.notification.AlarmService.refreshChannels(context)
        }
        dndAccessGranted = granted
        onPauseOrDispose { }
    }

    // System ringtone picker for the alarm sound.
    val soundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val title = uri?.let { RingtoneManager.getRingtone(context, it)?.getTitle(context) } ?: "Silent"
        prefs.setAlarmSound(uri?.toString() ?: "", title)
    }

    SettingsSubScreen("Target & alarms", onBack) {
        SettingsSection("Target range") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${fmt(low, unit)} – ${fmt(high, unit)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { showRangeDialog = true }.padding(vertical = 4.dp),
                )
                RangeSlider(
                    value = low..high,
                    onValueChange = { r ->
                        prefs.setTargetLow(r.start.roundToInt().toFloat())
                        prefs.setTargetHigh(r.endInclusive.roundToInt().toFloat())
                    },
                    valueRange = 40f..300f,
                )
                Text(
                    "Tap the values to type.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SwitchRow(null, "Show shaded band on graphs", checked = band, onCheckedChange = { prefs.setShowTargetBand(it) })
        }

        SettingsSection("Alarms") {
            SwitchRow(null, "Low alarm", subtitle = "Alert below ${fmt(low, unit)}", checked = lowAlarm, onCheckedChange = { prefs.setLowAlarmEnabled(it) })
            SwitchRow(null, "High alarm", subtitle = "Alert above ${fmt(high, unit)}", checked = highAlarm, onCheckedChange = { prefs.setHighAlarmEnabled(it) })
            SwitchRow(null, "Signal-loss alarm", subtitle = "Alert when readings stop", checked = loss, onCheckedChange = { prefs.setSignalLossAlarm(it) })
            SwitchRow(
                null,
                "Override Do Not Disturb",
                subtitle = "Ring and vibrate even while Do Not Disturb is on",
                checked = overrideDnd,
                onCheckedChange = { on ->
                    prefs.setAlarmOverrideDnd(on)
                    de.mogluco.notification.AlarmService.refreshChannels(context)
                    // The override needs Do-Not-Disturb (notification-policy) access, a special
                    // grant only the user can give: send them straight to the system list.
                    if (on && !hasDndAccess(context)) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                            )
                        }
                    }
                },
            )
            // dndAccessGranted is refreshed on resume by the LifecycleResumeEffect above.
            if (overrideDnd && !dndAccessGranted) {
                NavRow(
                    icon = null,
                    title = "Do Not Disturb access needed",
                    subtitle = "Without it alarms may stay silenced by Do Not Disturb — tap to allow access for Mogluco",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                            )
                        }
                    },
                )
            }
            if (!canFullScreenAlarm) {
                NavRow(
                    icon = null,
                    title = "Full-screen alarms are blocked",
                    subtitle = "Alarm screen may not appear when locked — tap to allow full-screen alarms",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                                    .setData(android.net.Uri.parse("package:${context.packageName}")),
                            )
                        }
                    },
                )
            }
        }

        SettingsSection("Repeat") {
            // Both steppers and the typed dialog share REPEAT_MINUTES_RANGE. 0 = "no repeat":
            // the alarm won't re-fire for an ongoing excursion; only a new one (back in range,
            // then out again) re-alerts.
            StepperRow(
                "Out-of-range alarm",
                if (repeat == 0) "No repeat — only a new low/high re-alerts" else "Re-alert every $repeat min while out of range",
                value = if (repeat == 0) "Off" else "$repeat",
                onMinus = { prefs.setAlarmRepeatMinutes((repeat - 5).coerceIn(REPEAT_MINUTES_RANGE)) },
                onPlus = { prefs.setAlarmRepeatMinutes((repeat + 5).coerceIn(REPEAT_MINUTES_RANGE)) },
                onValueClick = { editRepeat = "alarm" },
            )
            StepperRow(
                "Reminder",
                if (reminderRepeat == 0) "Snooze won't re-alert" else "Repeat reminder every $reminderRepeat min after snooze",
                value = if (reminderRepeat == 0) "Off" else "$reminderRepeat",
                onMinus = { prefs.setReminderRepeatMinutes((reminderRepeat - 5).coerceIn(REPEAT_MINUTES_RANGE)) },
                onPlus = { prefs.setReminderRepeatMinutes((reminderRepeat + 5).coerceIn(REPEAT_MINUTES_RANGE)) },
                onValueClick = { editRepeat = "reminder" },
            )
            StepperRow(
                "Alarm duration",
                "Rings for $timeout s, then auto-dimisses",
                value = "${timeout}s",
                onMinus = { prefs.setAlarmTimeoutSeconds((timeout - 15).coerceAtLeast(15)) },
                onPlus = { prefs.setAlarmTimeoutSeconds((timeout + 15).coerceAtMost(600)) },
                onValueClick = { editRepeat = "timeout" },
            )
            StepperRow(
                "Signal-loss delay",
                "Trigger the signal-loss alarm/notification after $signalLossMin min without readings",
                value = "$signalLossMin",
                onMinus = { prefs.setSignalLossMinutes((signalLossMin - 5).coerceIn(SIGNAL_LOSS_MINUTES_RANGE)) },
                onPlus = { prefs.setSignalLossMinutes((signalLossMin + 5).coerceIn(SIGNAL_LOSS_MINUTES_RANGE)) },
                onValueClick = { editRepeat = "signalloss" },
            )
        }

        SettingsSection("Sound & vibration") {
            SwitchRow(null, "Sound", checked = sound, onCheckedChange = { prefs.setAlarmSound(it) })
            NavRow(
                icon = null,
                title = "Alarm sound",
                value = soundTitle,
                onClick = {
                    val intent = android.content.Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        if (soundUri.isNotEmpty()) {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, android.net.Uri.parse(soundUri))
                        }
                    }
                    soundPicker.launch(intent)
                },
            )
            SwitchRow(null, "Vibrate", checked = vibrate, onCheckedChange = { prefs.setAlarmVibrate(it) })
            if (vibrate) {
                ChoiceRow(
                    label = "Vibration pattern",
                    options = VibrationPattern.entries,
                    selected = pattern,
                    optionLabel = { it.label },
                    onSelect = { prefs.setVibrationPattern(it); vibrateOnce(context, it) },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Rings continuously (sound + vibration) until stopped or it times out — the same
                // path real alarms use, so it's a true preview and is dismissable.
                OutlinedButton(onClick = { de.mogluco.notification.AlarmController.test(context) }) {
                    Text("Test alarm")
                }
                OutlinedButton(onClick = { de.mogluco.notification.AlarmService.dismiss(context) }) {
                    Text("Stop")
                }
            }
        }
    }

    if (showRangeDialog) {
        TargetRangeDialog(
            lowMgdl = low.roundToInt(),
            highMgdl = high.roundToInt(),
            unit = unit,
            onConfirm = { lo, hi -> prefs.setTargetLow(lo.toFloat()); prefs.setTargetHigh(hi.toFloat()) },
            onDismiss = { showRangeDialog = false },
        )
    }
    editRepeat?.let { which ->
        val title = when (which) {
            "alarm" -> "Out-of-range repeat (min)"
            "reminder" -> "Reminder repeat (min)"
            "timeout" -> "Alarm duration (s)"
            else -> "Signal-loss delay (min)"
        }
        NumberInputDialog(
            title = title,
            initial = when (which) {
                "alarm" -> repeat
                "reminder" -> reminderRepeat
                "timeout" -> timeout
                else -> signalLossMin
            },
            suffix = if (which == "timeout") "s" else "min",
            onConfirm = { v ->
                when (which) {
                    "alarm" -> prefs.setAlarmRepeatMinutes(v.coerceIn(REPEAT_MINUTES_RANGE))
                    "reminder" -> prefs.setReminderRepeatMinutes(v.coerceIn(REPEAT_MINUTES_RANGE))
                    "timeout" -> prefs.setAlarmTimeoutSeconds(v.coerceIn(15, 600))
                    else -> prefs.setSignalLossMinutes(v.coerceIn(SIGNAL_LOSS_MINUTES_RANGE))
                }
            },
            onDismiss = { editRepeat = null },
        )
    }
}

/** Shared bounds for the out-of-range/reminder repeat interval. 0 = no repeat (only a new excursion re-alerts). */
private val REPEAT_MINUTES_RANGE = 0..240

/** Bounds for the signal-loss delay (minutes without readings before alerting). */
private val SIGNAL_LOSS_MINUTES_RANGE = 5..60

/**
 * Whether full-screen alarm intents may launch. API 34+ can deny them per-app (and defaults to
 * denied for sideloaded apps); before that the manifest permission alone suffices.
 */
private fun canUseFullScreenIntent(context: android.content.Context): Boolean =
    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        (context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .canUseFullScreenIntent()

/** Whether the user granted Mogluco Do-Not-Disturb (notification-policy) access. */
private fun hasDndAccess(context: android.content.Context): Boolean =
    (context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
        .isNotificationPolicyAccessGranted

/** A one-shot vibration to preview a pattern when the user picks it in settings. */
private fun vibrateOnce(context: android.content.Context, pattern: VibrationPattern) {
    val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
            as android.os.VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
    }
    vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern.timings, -1))
}

// ---------------- Notifications ----------------

@Composable
fun NotificationSettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val prefs = viewModel.prefs
    val context = LocalContext.current
    val persist by prefs.persistentNotification.collectAsStateWithLifecycle()
    val lockscreen by prefs.showOnLockscreen.collectAsStateWithLifecycle()
    val signalLossNotif by prefs.signalLossNotification.collectAsStateWithLifecycle()
    val signalLossMin by prefs.signalLossMinutes.collectAsStateWithLifecycle()
    val alwaysOn by prefs.alwaysOnDisplay.collectAsStateWithLifecycle()
    val aodPositionPercent by prefs.aodPositionPercent.collectAsStateWithLifecycle()
    // Re-read on every resume: coming back from the system settings screen does NOT recompose,
    // so values computed during composition went stale and the enable loop felt broken (grant
    // the permission, return, row still says disabled).
    var aodEnabled by remember { mutableStateOf(AodOverlayService.isEnabled(context)) }
    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    var ignoringBattery by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        aodEnabled = AodOverlayService.isEnabled(context)
        ignoringBattery = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    var showAodDialog by remember { mutableStateOf(false) }
    var showAodPosDialog by remember { mutableStateOf(false) }

    SettingsSubScreen("Notifications", onBack) {
        SettingsSection("Persistent notification") {
            SwitchRow(
                null,
                "Show current reading",
                // Alarms need a foreground service, so turning this off only slims the
                // notification down — it doesn't remove it entirely.
                subtitle = "Full reading, trend and mini-graph in the status bar.",
                checked = persist,
                onCheckedChange = { prefs.setPersistentNotification(it) },
            )
            SwitchRow(
                null,
                "Show on lockscreen",
                subtitle = "Also lets the always-on overlay show on the lock screen, not only when " +
                    "the screen is off.",
                checked = lockscreen,
                onCheckedChange = { prefs.setShowOnLockscreen(it) },
            )
        }
        SettingsSection("Signal loss") {
            SwitchRow(
                null,
                "Signal-loss notification",
                subtitle = "Notify after $signalLossMin min without new " +
                    "readings. Set the delay under Target & alarms.",
                checked = signalLossNotif,
                onCheckedChange = { prefs.setSignalLossNotification(it) },
            )
        }
        // Opt-in overlay AOD. Separate from the notification above: it draws the reading and graph
        // over the lock screen / AOD via an Accessibility service. The notification keeps working
        // regardless. The permission is only ever requested when the user enables this.
        SettingsSection("True always-on display") {
            SwitchRow(
                null,
                "Overlay on lock screen & AOD",
                subtitle = "Draw the reading and graph over the lock screen / always-on display. " +
                    "Needs the Accessibility permission.",
                checked = alwaysOn,
                onCheckedChange = { on ->
                    prefs.setAlwaysOnDisplay(on)
                    // Only prompt for the permission when the user actually opts in.
                    if (on && !AodOverlayService.isEnabled(context)) showAodDialog = true
                },
            )
            if (alwaysOn) {
                if (aodEnabled) {
                    InfoRow("Accessibility permission", "Enabled ✓")
                } else {
                    NavRow(
                        icon = null,
                        title = "Enable in Accessibility settings",
                        subtitle = "Tap to grant the permission that lets the overlay draw",
                        onClick = { AodOverlayService.openAccessibilitySettings(context) },
                    )
                }
                // Fine vertical position: a slider (tap the % to type it) over the Top/Center/Bottom
                // presets, which just set the slider to their predefined values.
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Position", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${aodPositionPercent.roundToInt()}% from top",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { showAodPosDialog = true }.padding(vertical = 4.dp),
                    )
                    Slider(
                        value = aodPositionPercent,
                        onValueChange = { prefs.setAodPositionPercent(it) },
                        valueRange = 0f..100f,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AodPosition.entries.forEach { preset ->
                            OutlinedButton(onClick = { prefs.setAodPositionPercent(preset.percent) }) {
                                Text(preset.label)
                            }
                        }
                    }
                    Text(
                        "Tap the value to type.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SettingsSection("Background") {
            // ignoringBattery is refreshed on resume by the LifecycleResumeEffect above.
            if (ignoringBattery) {
                InfoRow("Battery optimization", "Unrestricted ✓")
                Text(
                    "Mogluco can run in the background, so readings keep arriving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            } else {
                NavRow(
                    icon = null,
                    title = "Battery optimization",
                    subtitle = "Allow Mogluco to run unrestricted so readings keep arriving",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(android.net.Uri.parse("package:${context.packageName}")),
                            )
                        }.onFailure {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    },
                )
            }
        }
    }

    if (showAodPosDialog) {
        NumberInputDialog(
            title = "Position (% from top)",
            initial = aodPositionPercent.roundToInt(),
            suffix = "%",
            onConfirm = { prefs.setAodPositionPercent(it.toFloat()) },
            onDismiss = { showAodPosDialog = false },
        )
    }

    if (showAodDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAodDialog = false },
            title = { Text("Enable always-on overlay") },
            text = {
                Text(
                    "To show your glucose over the lock screen and always-on display, Mogluco uses an " +
                        "Accessibility service to draw on screen. It does not read your screen content.\n\n" +
                        "Note: some banking apps may refuse to run while an accessibility service is enabled, " +
                        "and on some phones you must first allow \"Restricted settings\" for Mogluco. " +
                        "You can turn this off anytime.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showAodDialog = false
                    AodOverlayService.openAccessibilitySettings(context)
                }) { Text("Open settings") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showAodDialog = false }) { Text("Cancel") }
            },
        )
    }
}

// ---------------- small shared rows ----------------

@Composable
private fun InfoRow(
    label: String,
    value: String,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (trailingIcon != null) {
            Spacer(Modifier.size(8.dp))
            Icon(trailingIcon, contentDescription = "Copy", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun StepperRow(
    title: String,
    subtitle: String?,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onValueClick: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FilledIconButton(onClick = onMinus, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = "decrease")
        }
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .let { if (onValueClick != null) it.clickable(onClick = onValueClick) else it }
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
        FilledIconButton(onClick = onPlus, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "increase")
        }
    }
}

/** "<value> <unit>", reusing the canonical conversion + rounding in [GlucoseUnit]. */
private fun fmt(mgdl: Float, unit: GlucoseUnit): String = "${unit.format(mgdl.toDouble())} ${unit.label}"

private fun dateLabel(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
