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
package de.mogluco.wear.ui.settings

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tk.glucodata.Natives
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import de.mogluco.data.GlucoseRepository
import de.mogluco.wear.WearApp
import de.mogluco.wear.WearPreferences
import de.mogluco.wear.canUseFullScreenAlarm
import de.mogluco.wear.requestBatteryExemption
import de.mogluco.wear.requestFullScreenAlarmAccess
import de.mogluco.wear.alarm.WearAlarmController
import de.mogluco.wear.alarm.WearAlarmService
import tk.glucodata.WatchLink

/** Whether the watch app holds Do-Not-Disturb (notification-policy) access. */
private fun hasDndAccess(context: android.content.Context): Boolean =
    (context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
        .isNotificationPolicyAccessGranted

/**
 * Watch settings: the on-watch alarm switches and sensor status. Everything else
 * (unit, target range, thresholds, sensor management) is configured on the phone —
 * the watch is a companion, not a second control panel.
 */
@Composable
fun WearSettingsScreen(
    preferences: WearPreferences,
    repository: GlucoseRepository,
) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    // Does THIS watch currently hold the sensor's own BLE connection? Only then does "Hand sensor
    // back" make sense. The native flag flips when the phone hands the sensor over or reclaims it;
    // poll it so the button appears/disappears with ownership. Lifecycle-gated and polled every
    // 10 s (checking immediately on entry/resume) — ownership changes are rare and user-initiated,
    // so a tight 3 s loop of JNI calls was pure battery cost.
    val settingsLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val ownsSensor by produceState(false, settingsLifecycleOwner) {
        settingsLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                value = runCatching { Natives.getusebluetooth() }.getOrDefault(false)
                kotlinx.coroutines.delay(10_000)
            }
        }
    }
    val low by preferences.lowAlarmEnabled.collectAsState()
    val high by preferences.highAlarmEnabled.collectAsState()
    val loss by preferences.signalLossAlarm.collectAsState()
    val dnd by preferences.alarmOverrideDnd.collectAsState()
    val vibrate by preferences.alarmVibrate.collectAsState()
    val sound by preferences.alarmSound.collectAsState()
    val persistNotif by preferences.persistentNotification.collectAsState()
    val sensor by repository.sensor.collectAsState(initial = null)
    var dndAccessGranted by remember { mutableStateOf(hasDndAccess(context)) }
    var fullScreenGranted by remember { mutableStateOf(canUseFullScreenAlarm(context)) }
    val powerManager = context.getSystemService(android.os.PowerManager::class.java)
    var ignoringBattery by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true)
    }
    // Re-check when returning from a system screen so the status rows clear.
    LifecycleResumeEffect(Unit) {
        dndAccessGranted = hasDndAccess(context)
        fullScreenGranted = canUseFullScreenAlarm(context)
        ignoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        onPauseOrDispose { }
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Alarms") } }
            item {
                SwitchButton(
                    checked = low,
                    onCheckedChange = { preferences.setLowAlarmEnabled(it) },
                    label = { Text("Low glucose") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SwitchButton(
                    checked = high,
                    onCheckedChange = { preferences.setHighAlarmEnabled(it) },
                    label = { Text("High glucose") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SwitchButton(
                    checked = loss,
                    onCheckedChange = { preferences.setSignalLossAlarm(it) },
                    label = { Text("Signal loss") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SwitchButton(
                    checked = vibrate,
                    onCheckedChange = { preferences.setAlarmVibrate(it) },
                    label = { Text("Vibrate") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SwitchButton(
                    checked = sound,
                    onCheckedChange = { preferences.setAlarmSound(it) },
                    label = { Text("Sound") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SwitchButton(
                    checked = persistNotif,
                    onCheckedChange = {
                        preferences.setPersistentNotification(it)
                        // Apply now instead of waiting for the next reading to rebuild the chip.
                        de.mogluco.wear.service.WearGlucoseService.refresh(context)
                    },
                    label = { Text("Glucose notification") },
                    secondaryLabel = { Text("Off keeps only a minimal background notice") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SwitchButton(
                    checked = dnd,
                    onCheckedChange = { on ->
                        preferences.setAlarmOverrideDnd(on)
                        // The bypass flag only takes effect when re-stamped on the channel.
                        WearAlarmService.refreshChannel(context)
                        // Android honors "ring through DND" only while the app holds
                        // notification-policy access; without it the toggle is inert. Send the user
                        // to grant it, and reflect the result in the status row below.
                        if (on && !hasDndAccess(context)) {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                        dndAccessGranted = hasDndAccess(context)
                    },
                    label = { Text("Ring through DND") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (dnd && !dndAccessGranted) {
                item {
                    Card(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("DND access needed", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Without it, alarms stay silenced by Do Not Disturb. Tap to allow.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!fullScreenGranted) {
                item {
                    Card(
                        onClick = { requestFullScreenAlarmAccess(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Full-screen alarms", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Lets an alarm open full-screen from the background — so you see it, and " +
                                "can dismiss it with the crown, without opening notifications. Tap to allow.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { ListHeader { Text("Alarm test") } }
            item {
                Button(
                    onClick = { WearAlarmController.test(context) },
                    label = { Text("Test alarm") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { ListHeader { Text("Sensor") } }
            item {
                val s = sensor
                Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    if (s == null) {
                        Text("No sensor", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Scan with the phone, then send it here from the phone's watch settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(s.id, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Day ${s.dayOfLife()} of ${s.lifetimeDays}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { ListHeader { Text("Phone sync") } }
            if (WearApp.USE_SAMPLE_DATA) {
                item {
                    Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text("Sample-data mode", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "The mirror runs on the live engine only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item {
                    val phones by WatchLink.nodes.collectAsState()
                    Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        val phone = phones.firstOrNull()
                        Text(
                            phone?.name ?: "Phone not reachable",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            if (WatchLink.initStarted) "Synced with the phone app" else "Waiting for the phone app…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    val phones by WatchLink.nodes.collectAsState()
                    val scope = rememberCoroutineScope()
                    var syncing by remember { mutableStateOf(false) }
                    Button(
                        onClick = {
                            if (phones.isEmpty()) {
                                Toast.makeText(
                                    context,
                                    "Phone not reachable — keep it nearby with Bluetooth on.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                WatchLink.reinit()
                                syncing = true
                                Toast.makeText(context, "Re-syncing with the phone…", Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    // reinit() requests a catch-up; report its outcome (or a timeout)
                                    // instead of firing blind. The phone answers within a couple of
                                    // seconds when reachable.
                                    val n = withTimeoutOrNull(15_000) { WatchLink.catchupEvents.first() }
                                    syncing = false
                                    val msg = when {
                                        n == null -> "No response from the phone — try again with it nearby."
                                        n == 0 -> "Synced — already up to date."
                                        else -> "Synced — filled $n reading" + (if (n == 1) "" else "s") + "."
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !syncing,
                        label = { Text(if (syncing) "Syncing…" else "Re-sync") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Only meaningful when THIS watch holds the sensor — hidden when the phone owns it.
                if (ownsSensor) {
                    item {
                        Button(
                            onClick = {
                                // requestHandBack reports whether it actually reached the phone, so
                                // the toast reflects reality instead of firing blind.
                                val sent = WatchLink.requestHandBack()
                                Toast.makeText(
                                    context,
                                    if (sent) {
                                        "Asked the phone to take the sensor back"
                                    } else {
                                        "Phone not reachable — keep it nearby with Bluetooth on, then retry"
                                    },
                                    if (sent) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                                ).show()
                            },
                            label = { Text("Hand sensor back") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item { ListHeader { Text("Background") } }
            item {
                if (ignoringBattery) {
                    // Already unrestricted — just confirm, nothing to tap.
                    Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text("Background running access granted", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Card(onClick = { requestBatteryExemption(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow background running", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Opens app settings — set Battery to \"Unrestricted\" so the sensor keeps " +
                                "syncing without opening the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
