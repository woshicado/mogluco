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

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayForWork
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import kotlin.math.roundToInt
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import de.mogluco.MoglucoApp
import de.mogluco.data.AppPreferences
import tk.glucodata.WatchLink

/**
 * Settings → Watch: pairing status, watch-app bootstrap, the sensor transfer (with the
 * ack/auto-revert state machine made visible), and the opt-in auto-reclaim.
 */
@Composable
fun WatchScreen(
    prefs: AppPreferences,
    onBack: () -> Unit,
) {
    SettingsSubScreen(title = "Watch", onBack = onBack) {
        if (!WatchLink.isInitialized) {
            SettingsSection("Watch sync") {
                Column(Modifier.padding(16.dp)) {
                    Text("Not available in sample-data mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "The phone↔watch mirror runs on the live engine. Flip USE_SAMPLE_DATA off to use it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@SettingsSubScreen
        }

        val nodes by WatchLink.nodes.collectAsStateWithLifecycle()
        val transfer by WatchLink.transferState.collectAsStateWithLifecycle()
        val autoReclaim by prefs.autoReclaimEnabled.collectAsState()
        val reclaimMinutes by prefs.autoReclaimMinutes.collectAsState()
        val waRepeat by prefs.watchAlarmRepeat.collectAsState()
        val waSnooze by prefs.watchAlarmSnooze.collectAsState()
        val waTimeout by prefs.watchAlarmTimeout.collectAsState()
        var editWatchAlarm by remember { mutableStateOf<String?>(null) }
        var initing by remember { mutableStateOf(false) }
        var resyncing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        // Ticks so "last data … ago" and the awaiting countdown stay current.
        val now by produceState(System.currentTimeMillis()) {
            while (true) {
                delay(5_000)
                value = System.currentTimeMillis()
            }
        }

        val watch = nodes.firstOrNull()
        val context = LocalContext.current
        val app = context.applicationContext as MoglucoApp
        val sensorInfo by app.glucoseRepository.sensor.collectAsState(initial = null)
        val latest by app.glucoseRepository.latest.collectAsState(initial = null)
        // A transfer only makes sense if the phone actually has a live sensor to hand over: a
        // recent reading (streaming) or a sensor still in warm-up (connected, not yet streaming).
        val hasLiveSensor = (latest?.let { !it.isStale(now) } == true) ||
            (sensorInfo?.isWarmingUp(now) == true)

        SettingsSection("Paired watch") {
            // Informational row (not a navigation target) — no chevron.
            NavRow(
                Icons.Filled.Watch,
                watch?.name ?: "No watch reachable",
                subtitle = if (watch != null) {
                    "Connected via Wear OS"
                } else {
                    "Install the Mogluco watch app and keep Bluetooth on"
                },
                trailingIcon = null,
                onClick = {},
            )
            NavRow(
                Icons.Filled.PlayForWork,
                "Init watch app",
                subtitle = "Send settings + start the initial data sync",
                trailingIcon = null,
                busy = initing,
                onClick = {
                    if (!initing) {
                        val w = watch
                        if (w == null) {
                            Toast.makeText(
                                context,
                                "No watch reachable — open Mogluco on the watch and keep Bluetooth on",
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            initing = true
                            Toast.makeText(context, "Sending settings to the watch…", Toast.LENGTH_SHORT).show()
                            WatchLink.sendInitWatchApp(w.id) { ok, msg ->
                                // The result lands on an IO coroutine; hop to main to toast + clear spinner.
                                ContextCompat.getMainExecutor(context).execute {
                                    initing = false
                                    Toast.makeText(context, msg, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                },
            )
            NavRow(
                Icons.Filled.RestartAlt,
                "Re-sync",
                subtitle = "Re-handshake the mirror connection",
                trailingIcon = null,
                busy = resyncing,
                onClick = {
                    if (!resyncing) {
                        if (watch == null) {
                            Toast.makeText(
                                context,
                                "No watch reachable yet — the mirror will keep retrying",
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            resyncing = true
                            Toast.makeText(context, "Re-syncing the mirror connection…", Toast.LENGTH_SHORT).show()
                            WatchLink.reinit()
                            scope.launch {
                                // reinit() re-handshakes and requests a catch-up; report its outcome
                                // (or a timeout) instead of firing blind. The watch answers within a
                                // couple of seconds when reachable.
                                val n = withTimeoutOrNull(15_000) { WatchLink.catchupEvents.first() }
                                resyncing = false
                                val msg = when {
                                    n == null -> "Re-sync sent — no catch-up came back (the watch may already be in sync)."
                                    n == 0 -> "Re-synced — already in sync."
                                    else -> "Re-synced — pulled $n reading" + (if (n == 1) "" else "s") + " from the watch."
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
            )
        }

        SettingsSection("Sensor connection") {
            when (val t = transfer) {
                is WatchLink.TransferState.PhoneHasSensor -> {
                    SendToWatchRow(
                        watch,
                        canTransfer = hasLiveSensor,
                        subtitleWhenReady = "The watch connects to the sensor directly; values sync back here",
                    )
                }
                is WatchLink.TransferState.AwaitingWatchConfirm -> {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Waiting for the watch…", style = MaterialTheme.typography.bodyLarge)
                            val left = ((t.deadlineMillis - now) / 60_000 + 1).coerceAtLeast(0)
                            Text(
                                "Reverts to the phone automatically in ~$left min if the watch doesn't take over",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    NavRow(Icons.Filled.Undo, "Cancel and keep sensor on phone", onClick = {
                        WatchLink.takeBackSensor(t.nodeId)
                    })
                }
                is WatchLink.TransferState.WatchHasSensor -> {
                    // With the watch owning the sensor, the phone's own Bluetooth is off, so every
                    // reading shown came from the watch over the mirror. Base freshness on the actual
                    // last reading (matching the live value) rather than the mirror's periodic
                    // "sync-complete" marker (lastwatchdata), which updates far less often and made
                    // this read minutes-stale while readings were in fact arriving every minute.
                    val last = latest?.epochMillis?.takeIf { it > 0 } ?: WatchLink.lastWatchDataMillis(t.nodeId)
                    val age = if (last > 0) "${((now - last) / 60_000).coerceAtLeast(0)} min ago" else "no data yet"
                    // Informational (not a navigation target) — no chevron.
                    NavRow(
                        Icons.Filled.Watch,
                        "Watch has the sensor",
                        subtitle = "Last data from watch: $age",
                        trailingIcon = null,
                        onClick = {},
                    )
                    NavRow(
                        Icons.Filled.Undo,
                        "Take sensor back",
                        subtitle = "The phone reconnects to the sensor; the watch mirrors again",
                        onClick = { WatchLink.takeBackSensor(t.nodeId) },
                    )
                }
                is WatchLink.TransferState.RevertedAfterTimeout -> {
                    NavRow(
                        Icons.Filled.Undo,
                        "Transfer failed — sensor stayed on the phone",
                        subtitle = "The watch never confirmed. Check that the watch app runs, then try again",
                        onClick = {},
                    )
                    SendToWatchRow(watch, canTransfer = hasLiveSensor, subtitleWhenReady = "Try again")
                }
                is WatchLink.TransferState.ReclaimedFromWatch -> {
                    NavRow(
                        Icons.Filled.Undo,
                        "Sensor reclaimed from the watch",
                        subtitle = "The watch was unreachable too long; the phone reconnected",
                        onClick = {},
                    )
                    SendToWatchRow(watch, canTransfer = hasLiveSensor, subtitleWhenReady = "Hand the connection back to the watch")
                }
            }
        }

        SettingsSection("Watch alarms") {
            Text(
                "Thresholds follow the phone's target band; these timings apply on the watch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            StepperRow(
                "Repeat", null, if (waRepeat == 0) "Off" else "$waRepeat min",
                onMinus = { val v = (waRepeat - 5).coerceIn(0, 240); prefs.setWatchAlarmRepeat(v); WatchLink.pushWearAlarm(v, waSnooze, waTimeout) },
                onPlus = { val v = (waRepeat + 5).coerceIn(0, 240); prefs.setWatchAlarmRepeat(v); WatchLink.pushWearAlarm(v, waSnooze, waTimeout) },
                onValueClick = { editWatchAlarm = "repeat" },
            )
            StepperRow(
                "Snooze", null, if (waSnooze == 0) "Off" else "$waSnooze min",
                onMinus = { val v = (waSnooze - 5).coerceIn(0, 240); prefs.setWatchAlarmSnooze(v); WatchLink.pushWearAlarm(waRepeat, v, waTimeout) },
                onPlus = { val v = (waSnooze + 5).coerceIn(0, 240); prefs.setWatchAlarmSnooze(v); WatchLink.pushWearAlarm(waRepeat, v, waTimeout) },
                onValueClick = { editWatchAlarm = "snooze" },
            )
            StepperRow(
                "Alarm duration", null, "$waTimeout s",
                onMinus = { val v = (waTimeout - 15).coerceIn(15, 600); prefs.setWatchAlarmTimeout(v); WatchLink.pushWearAlarm(waRepeat, waSnooze, v) },
                onPlus = { val v = (waTimeout + 15).coerceIn(15, 600); prefs.setWatchAlarmTimeout(v); WatchLink.pushWearAlarm(waRepeat, waSnooze, v) },
                onValueClick = { editWatchAlarm = "timeout" },
            )
        }

        sectionAutoReclaim(prefs, autoReclaim, reclaimMinutes)

        editWatchAlarm?.let { which ->
            NumberInputDialog(
                title = when (which) {
                    "repeat" -> "Watch repeat (min)"
                    "snooze" -> "Watch snooze (min)"
                    else -> "Watch alarm duration (s)"
                },
                initial = when (which) {
                    "repeat" -> waRepeat
                    "snooze" -> waSnooze
                    else -> waTimeout
                },
                suffix = if (which == "timeout") "s" else "min",
                onConfirm = { v ->
                    when (which) {
                        "repeat" -> { val x = v.coerceIn(0, 240); prefs.setWatchAlarmRepeat(x); WatchLink.pushWearAlarm(x, waSnooze, waTimeout) }
                        "snooze" -> { val x = v.coerceIn(0, 240); prefs.setWatchAlarmSnooze(x); WatchLink.pushWearAlarm(waRepeat, x, waTimeout) }
                        else -> { val x = v.coerceIn(15, 600); prefs.setWatchAlarmTimeout(x); WatchLink.pushWearAlarm(waRepeat, waSnooze, x) }
                    }
                },
                onDismiss = { editWatchAlarm = null },
            )
        }
    }
}

/**
 * The one transfer entry point, shared by every state that offers it. It won't start a transfer
 * unless a watch is reachable AND the phone has a live sensor to hand over — otherwise the row
 * explains why and a tap just says so (there's nothing to send).
 */
@Composable
private fun SendToWatchRow(watch: WatchLink.WatchNode?, canTransfer: Boolean, subtitleWhenReady: String) {
    val context = LocalContext.current
    NavRow(
        Icons.Filled.Send,
        "Send sensor to watch",
        subtitle = when {
            watch == null -> "Needs a reachable watch"
            !canTransfer -> "No sensor connected — scan a sensor on the phone first"
            else -> subtitleWhenReady
        },
        trailingIcon = null,
        onClick = {
            when {
                watch == null -> Toast.makeText(
                    context,
                    "No watch reachable — open Mogluco on the watch and keep Bluetooth on",
                    Toast.LENGTH_LONG,
                ).show()
                !canTransfer -> Toast.makeText(
                    context,
                    "Connect a sensor on the phone first — there's nothing to send yet",
                    Toast.LENGTH_LONG,
                ).show()
                else -> WatchLink.requestTransferToWatch(watch.id)
            }
        },
    )
}

@Composable
private fun sectionAutoReclaim(prefs: AppPreferences, autoReclaim: Boolean, reclaimMinutes: Int) {
    SettingsSection("If the watch dies") {
            SwitchRow(
                icon = null,
                title = "Auto-reclaim sensor",
                subtitle = "Phone takes the sensor back after the watch has been silent and unreachable",
                checked = autoReclaim,
                onCheckedChange = { prefs.setAutoReclaimEnabled(it) },
            )
            if (autoReclaim) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Silence window", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "$reclaimMinutes minutes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // Slide to set how long the watch may be silent + unreachable before the phone
                    // reclaims the sensor. Snapped to 5-minute steps over 10–120 min.
                    Slider(
                        value = reclaimMinutes.toFloat(),
                        onValueChange = { v ->
                            val snapped = (v / 5f).roundToInt() * 5
                            prefs.setAutoReclaimMinutes(snapped.coerceIn(WatchLink.MIN_RECLAIM_MINUTES, 120))
                        },
                        valueRange = WatchLink.MIN_RECLAIM_MINUTES.toFloat()..120f,
                        steps = (120 - WatchLink.MIN_RECLAIM_MINUTES) / 5 - 1,
                    )
                    Text(
                        "The phone reclaims the sensor after the watch has sent no data for this long " +
                            "and is unreachable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
}
