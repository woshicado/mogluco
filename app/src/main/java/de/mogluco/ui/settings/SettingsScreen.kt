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

import androidx.compose.foundation.clickable
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import de.mogluco.data.EventCategory
import de.mogluco.data.ReadingDisplay
import de.mogluco.data.ThemeMode
import de.mogluco.health.HealthConnectSync
import de.mogluco.ui.export.ExportSheet
import de.mogluco.ui.util.utcToLocalDay

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenSensor: () -> Unit,
    onOpenTargetAlarms: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenWatch: () -> Unit,
    onOpenEventLogging: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = viewModel.prefs
    val unit by prefs.unit.collectAsStateWithLifecycle()
    val theme by prefs.themeMode.collectAsStateWithLifecycle()
    val display by prefs.readingDisplay.collectAsStateWithLifecycle()
    val allowLandscape by prefs.allowLandscape.collectAsStateWithLifecycle()
    val gapMinutes by prefs.gapThresholdMinutes.collectAsStateWithLifecycle()
    val smoothing by prefs.smoothing.collectAsStateWithLifecycle()
    val smoothingAlpha by prefs.smoothingAlpha.collectAsStateWithLifecycle()
    val sensor by viewModel.sensor.collectAsStateWithLifecycle()

    var showExport by remember { mutableStateOf(false) }
    var showGapDialog by remember { mutableStateOf(false) }
    var showAlphaDialog by remember { mutableStateOf(false) }
    var showDeleteDate by remember { mutableStateOf(false) }
    var deleteBeforeMillis by remember { mutableStateOf<Long?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && !restoring) {
            restoring = true
            scope.launch {
                // Reading + un-gzipping a backup of up to ~300k points is multi-second work;
                // doing it right here in the ActivityResult callback ran on Main and could ANR.
                val backup = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            de.mogluco.ui.export.DataExporter.readBackup(input)
                        }
                    }.getOrNull()
                }
                val message = when {
                    backup == null -> "Couldn't read that backup file"
                    // applyRestore reports whether the glucose points made it in — on native
                    // builds the engine owns glucose storage, so only settings/events restore.
                    // NonCancellable: the scope dies on rotation — a restore must not stop
                    // halfway through mutating prefs + the event store.
                    withContext(kotlinx.coroutines.NonCancellable) { viewModel.applyRestore(backup) } ->
                        "Backup restored"
                    else -> "Settings & events restored — glucose history can't be imported on this device"
                }
                restoring = false
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

            SettingsSection("Sensor") {
                NavRow(
                    Icons.Filled.Sensors,
                    "Sensor & calibration",
                    subtitle = sensor?.let { "ID ${it.id} · day ${it.dayOfLife()} / ${it.lifetimeDays}" }
                        ?: "No sensor active",
                    onClick = onOpenSensor,
                )
            }

            SettingsSection("Glucose") {
                NavRow(
                    Icons.Filled.Tune,
                    "Target range & alarms",
                    subtitle = "Range, shaded band, out-of-range alerts",
                    onClick = onOpenTargetAlarms,
                )
                ChoiceRow(
                    label = "Unit",
                    options = de.mogluco.data.GlucoseUnit.entries,
                    selected = unit,
                    optionLabel = { it.label },
                    onSelect = { prefs.setUnit(it) },
                )
                ChoiceRow(
                    label = "Readings shown",
                    options = ReadingDisplay.entries,
                    // With smoothing off there's only one trace to show, so lock this to Raw and grey
                    // out Smoothed / Both.
                    selected = if (smoothing) display else ReadingDisplay.RAW,
                    optionLabel = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                    onSelect = { prefs.setReadingDisplay(it) },
                    enabledOption = { smoothing || it == ReadingDisplay.RAW },
                )
                SwitchRow(
                    icon = null,
                    title = "Smooth the graph",
                    subtitle = "Show a smoothed (EMA) line.",
                    checked = smoothing,
                    onCheckedChange = { on ->
                        prefs.setSmoothing(on)
                        // No smoothed trace to display any more → fall back to Raw.
                        if (!on) prefs.setReadingDisplay(ReadingDisplay.RAW)
                    },
                )
                if (smoothing) {
                    val strengthPct = ((1f - smoothingAlpha) * 100).roundToInt()
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
                        Text(
                            "Strength: $strengthPct%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            modifier = Modifier.clickable { showAlphaDialog = true }.padding(vertical = 4.dp),
                        )
                        androidx.compose.material3.Slider(
                            value = 1f - smoothingAlpha, // slider = strength; right = smoother
                            onValueChange = { prefs.setSmoothingAlpha((1f - it).coerceIn(0.05f, 0.9f)) },
                            valueRange = 0.1f..0.95f,
                        )
                        Text(
                            "Higher = stronger smoothing (more lag).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SettingsSection("Notifications") {
                NavRow(
                    Icons.Filled.NotificationsActive,
                    "Notification settings",
                    subtitle = "Always-on reading, lockscreen, always-on display, ...",
                    onClick = onOpenNotifications,
                )
            }

            SettingsSection("Watch") {
                NavRow(
                    Icons.Filled.Watch,
                    "Wear OS watch",
                    subtitle = "Sync, sensor transfer, auto-reclaim",
                    onClick = onOpenWatch,
                )
            }

            SettingsSection("Data") {
                NavRow(Icons.Filled.FileDownload, "Export data", subtitle = "CSV / TSV, LibreView, full backup") {
                    showExport = true
                }
                NavRow(
                    Icons.Filled.Restore,
                    "Restore backup",
                    subtitle = if (restoring) "Restoring…" else "Import data + settings from a backup file",
                ) {
                    if (!restoring) importLauncher.launch(arrayOf("*/*"))
                }
                NavRow(Icons.Filled.DeleteSweep, "Delete historic data", subtitle = "Remove readings & events before a date") {
                    showDeleteDate = true
                }
                HealthConnectRow(prefs)
                LibreViewDataRows(prefs)
            }

            SettingsSection("Appearance") {
                ChoiceRow(
                    label = "Theme",
                    options = ThemeMode.entries,
                    selected = theme,
                    optionLabel = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                    onSelect = { prefs.setThemeMode(it) },
                )
                SwitchRow(
                    Icons.Filled.ScreenRotation,
                    "Allow landscape",
                    subtitle = "Rotate to landscape; off keeps the app portrait",
                    checked = allowLandscape,
                    onCheckedChange = { prefs.setAllowLandscape(it) },
                )
            }

            SettingsSection("Graph") {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Break line after a $gapMinutes min gap",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { showGapDialog = true }
                            .padding(vertical = 4.dp),
                    )
                    androidx.compose.material3.Slider(
                        value = gapMinutes.toFloat(),
                        onValueChange = { prefs.setGapThresholdMinutes(it.roundToInt().coerceIn(5, 180)) },
                        valueRange = 5f..180f,
                    )
                    Text(
                        "A no-data gap longer than this isn't joined by a straight line.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsSection("Event logging") {
                NavRow(
                    Icons.AutoMirrored.Filled.DirectionsRun,
                    "What to log",
                    subtitle = "Choose which kinds of events appear in the + button, graph and legend",
                    onClick = onOpenEventLogging,
                )
            }

            // Feedback + support — both leave the app, so they show an open-in-new icon.
            SettingsSection("Support") {
                NavRow(
                    Icons.Filled.MailOutline,
                    "Send feedback",
                    subtitle = "Email the developer",
                    trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = {
                        // openUri throws when nothing handles mailto: (no e-mail app installed);
                        // fall back to a toast naming the address instead of crashing.
                        runCatching {
                            uriHandler.openUri("mailto:$FEEDBACK_EMAIL?subject=${android.net.Uri.encode("Mogluco feedback")}")
                        }.onFailure {
                            android.widget.Toast.makeText(
                                context,
                                "No e-mail app found — write to $FEEDBACK_EMAIL",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )
                NavRow(
                    Icons.Filled.Coffee,
                    "Buy me a Coffee",
                    subtitle = "Support development on Ko-fi",
                    trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                    // Anchor straight to the tip/checkout dialog rather than the profile feed.
                    onClick = { uriHandler.openUri("https://ko-fi.com/woshicado#checkoutModal") },
                )
            }
        }
    }

    if (showGapDialog) {
        NumberInputDialog(
            title = "Gap before break (min)",
            initial = gapMinutes,
            suffix = "min",
            onConfirm = { prefs.setGapThresholdMinutes(it.coerceIn(5, 180)) },
            onDismiss = { showGapDialog = false },
        )
    }

    if (showAlphaDialog) {
        NumberInputDialog(
            title = "Smoothing strength (%)",
            initial = ((1f - smoothingAlpha) * 100).roundToInt(),
            suffix = "%",
            onConfirm = { pct -> prefs.setSmoothingAlpha(1f - pct.coerceIn(5, 95) / 100f) },
            onDismiss = { showAlphaDialog = false },
        )
    }

    if (showExport) {
        val history by viewModel.history.collectAsStateWithLifecycle()
        val events by viewModel.events.collectAsStateWithLifecycle()
        ExportSheet(
            points = history,
            events = events,
            unit = unit,
            serial = sensor?.id ?: "",
            settings = prefs.exportAll(),
            onDismiss = { showExport = false },
        )
    }

    if (showDeleteDate) {
        DeleteDatePicker(
            onPick = { showDeleteDate = false; deleteBeforeMillis = it },
            onDismiss = { showDeleteDate = false },
        )
    }
    deleteBeforeMillis?.let { cutoff ->
        DeleteConfirmDialog(
            cutoff = cutoff,
            // On the live engine glucose history is engine-owned and deleteBefore only removes
            // events — say so up front instead of promising a full wipe (sample mode, without
            // the engine, really does delete both).
            glucoseDeletable = !tk.glucodata.Engine.isStarted(),
            onBackup = { deleteBeforeMillis = null; showExport = true },
            onConfirm = {
                val glucoseDeleted = viewModel.deleteHistoryBefore(cutoff)
                android.widget.Toast.makeText(
                    context,
                    if (glucoseDeleted) {
                        "Deleted data before ${dateLabel(cutoff)}"
                    } else {
                        "Events before ${dateLabel(cutoff)} deleted — sensor readings are kept by the engine"
                    },
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                deleteBeforeMillis = null
            },
            onDismiss = { deleteBeforeMillis = null },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DeleteDatePicker(onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val state = androidx.compose.material3.rememberDatePickerState()
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                // The picker reports UTC midnight; used raw as a cutoff it's a full day off in
                // UTC-negative zones (and a mid-day boundary elsewhere) once formatted/compared
                // in local time — convert to the same calendar date's LOCAL midnight first.
                onClick = { state.selectedDateMillis?.let { onPick(utcToLocalDay(it)) } },
                enabled = state.selectedDateMillis != null,
            ) { Text("Next") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { androidx.compose.material3.DatePicker(state = state) }
}

@Composable
private fun DeleteConfirmDialog(
    cutoff: Long,
    glucoseDeletable: Boolean,
    onBackup: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { androidx.compose.material3.Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
        title = { Text("Delete data before ${dateLabel(cutoff)}?") },
        text = {
            Text(
                if (glucoseDeletable) {
                    "All readings and events before ${dateLabel(cutoff)} will be permanently deleted. " +
                        "This can't be undone — we strongly suggest exporting a full backup first."
                } else {
                    "Events before ${dateLabel(cutoff)} will be permanently deleted. Sensor readings " +
                        "are owned by the measurement engine and are kept. This can't be undone — " +
                        "we strongly suggest exporting a full backup first."
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Delete") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onBackup) { Text("Back up first") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun dateLabel(millis: Long): String =
    java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(millis))

/** Recipient for the "Send feedback" mailto link. */
private const val FEEDBACK_EMAIL = "feedback@woshicado.eu"

/**
 * Health Connect export toggle. Off by default; turning it on requests the WRITE_BLOOD_GLUCOSE
 * permission and only flips the pref on once it's granted, so the switch never reads "on" without a
 * working sync. Shows a disabled "unavailable" row when the platform SDK isn't present. The actual
 * writing happens in [HealthConnectSync].
 */
@Composable
private fun HealthConnectRow(prefs: de.mogluco.data.AppPreferences) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by prefs.healthConnectEnabled.collectAsStateWithLifecycle()
    val available = remember { HealthConnectSync.isAvailable(context) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        prefs.setHealthConnectEnabled(granted.containsAll(HealthConnectSync.permissions))
    }

    if (!available) {
        NavRow(
            icon = Icons.Filled.MonitorHeart,
            title = "Health Connect",
            subtitle = "Not available on this device",
            trailingIcon = null,
            onClick = {},
        )
        return
    }

    SwitchRow(
        icon = Icons.Filled.MonitorHeart,
        title = "Sync to Health Connect",
        subtitle = "Write new readings to Health Connect",
        checked = enabled,
        onCheckedChange = { on ->
            if (!on) {
                prefs.setHealthConnectEnabled(false)
            } else {
                // Flip on only after the WRITE permission is granted.
                scope.launch {
                    if (HealthConnectSync.hasWritePermission(context)) {
                        prefs.setHealthConnectEnabled(true)
                    } else {
                        permissionLauncher.launch(HealthConnectSync.permissions)
                    }
                }
            }
        },
    )
    if (enabled) {
        var pushing by remember { mutableStateOf(false) }
        NavRow(
            icon = null,
            title = if (pushing) "Pushing history…" else "Push history to Health Connect",
            subtitle = "Write all stored readings once (safe to repeat)",
            trailingIcon = null,
            onClick = {
                if (!pushing) {
                    pushing = true
                    scope.launch {
                        val repo = (context.applicationContext as de.mogluco.MoglucoApp).glucoseRepository
                        val n = HealthConnectSync.backfill(context, repo)
                        pushing = false
                        val msg = if (n < 0) "Grant Health Connect access first" else "Pushed $n readings to Health Connect"
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }
}

/**
 * LibreView upload controls mirrored into the Data panel (the full version lives in Sensor &
 * calibration): an auto-upload toggle and a one-shot "Push now", both engine-backed. Uploading needs
 * the LibreView account ID, entered on the Sensor screen — hence the note.
 */
@Composable
private fun LibreViewDataRows(prefs: de.mogluco.data.AppPreferences) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val engineLive = remember { runCatching { tk.glucodata.Engine.isStarted() }.getOrDefault(false) }
    val accountId by prefs.accountId.collectAsStateWithLifecycle()
    var uploadEnabled by remember {
        mutableStateOf(engineLive && runCatching { tk.glucodata.Engine.isUploadEnabled() }.getOrDefault(false))
    }
    var pushing by remember { mutableStateOf(false) }

    SwitchRow(
        icon = null,
        title = "Upload to LibreView",
        subtitle = "Send new readings to LibreView in the background",
        checked = uploadEnabled,
        onCheckedChange = {
            uploadEnabled = it
            if (engineLive) runCatching { tk.glucodata.Engine.setUploadEnabled(it) }
        },
    )
    NavRow(
        icon = null,
        title = if (pushing) "Pushing to LibreView…" else "Push to LibreView now",
        subtitle = "Send collected readings once (doesn't change the toggle)",
        trailingIcon = null,
        onClick = {
            if (!pushing && engineLive) {
                pushing = true
                scope.launch {
                    runCatching { tk.glucodata.Engine.pushOnce() }
                    kotlinx.coroutines.delay(1500)
                    pushing = false
                    val s = runCatching { tk.glucodata.Engine.libreviewStatus() }.getOrDefault("")
                    android.widget.Toast.makeText(context, s.ifBlank { "Push started" }, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        },
    )
    Text(
        if (accountId.isBlank()) {
            "Requires your LibreView account ID — set it in Sensor & calibration."
        } else {
            "Uses your LibreView account ID from Sensor & calibration."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
    )
}
