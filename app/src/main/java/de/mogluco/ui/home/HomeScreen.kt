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
package de.mogluco.ui.home

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import de.mogluco.data.EventCategory
import de.mogluco.data.GlucosePoint
import de.mogluco.data.GlucoseReading
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.LoggedEvent
import de.mogluco.data.SensorInfo
import de.mogluco.data.TrendArrow
import de.mogluco.data.unit
import de.mogluco.ui.theme.MoglucoTheme
import de.mogluco.ui.theme.colorFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onAddSensor: () -> Unit = {},
    onOpenSensorInfo: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var range by rememberSaveable { mutableStateOf(TimeRange.H6) }
    // Saveable like its siblings — a plain remember reset the legend filter on every rotation.
    var enabledCategories by rememberSaveable(stateSaver = EnabledCategoriesSaver) {
        mutableStateOf(EventCategory.entries.toSet())
    }
    var listMode by rememberSaveable { mutableStateOf(BottomListMode.READINGS) }
    var maximized by rememberSaveable { mutableStateOf(false) }

    // Both sheets survive configuration changes (rotation, dark-mode toggle): a plain remember
    // closed the sheet mid-entry and silently discarded whatever was typed.
    var editing by rememberSaveable(stateSaver = EventSheetTarget.Saver) {
        mutableStateOf<EventSheetTarget?>(null)
    }
    var readingDetail by rememberSaveable(stateSaver = ReadingDetailSaver) {
        mutableStateOf<GlucoseReading?>(null)
    }

    // Curated, ordered event vocabulary; Activity is dropped when the user disabled it.
    val displayTypes = remember(viewModel, state.enabledLogKinds) { viewModel.displayTypes(state.enabledLogKinds) }
    val legendCategories = remember(state.enabledCategories) {
        defaultLegendCategories.filter { it in state.enabledCategories }
    }

    // Lifecycle-gated 30s clock: the pager keeps neighbouring tabs composed, so an unguarded
    // ticker would keep waking up while the app is backgrounded. repeatOnLifecycle stops the loop
    // the moment the activity leaves STARTED and restarts it (with a fresh timestamp) on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    val now by produceState(System.currentTimeMillis(), lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                kotlinx.coroutines.delay(30_000)
            }
        }
    }
    // "Today"/"Yesterday" span a whole calendar day (local midnight → next midnight); the fixed
    // ranges span a duration back from now.
    val windowStart: Long
    val windowEnd: Long
    when (range) {
        TimeRange.TODAY -> {
            // DST-safe: the next local midnight is not always start+24h (23h/25h transition days).
            // -1 keeps the window half-open against the end-inclusive windowed() filters.
            val midnight = de.mogluco.ui.util.startOfDay(now)
            windowStart = midnight
            windowEnd = de.mogluco.ui.util.nextLocalMidnight(midnight) - 1
        }
        TimeRange.YESTERDAY -> {
            // Yesterday's local midnight → today's local midnight, DST-safe like TODAY above.
            val todayMidnight = de.mogluco.ui.util.startOfDay(now)
            windowStart = de.mogluco.ui.util.addDays(todayMidnight, -1)
            windowEnd = todayMidnight - 1
        }
        else -> {
            windowEnd = now
            windowStart = now - range.millis
        }
    }

    val onToggleCategory: (EventCategory) -> Unit = { cat ->
        enabledCategories = if (cat in enabledCategories) enabledCategories - cat else enabledCategories + cat
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        // Maximize only applies in portrait; landscape already gives the list full height.
        val isMaximized = maximized && !landscape
        val header = @Composable { compact: Boolean ->
            HeaderCards(state, now, onAddSensor, onOpenSensorInfo, compact = compact)
        }
        val chartCard = @Composable { mod: Modifier, chartHeight: Dp?, showLegend: Boolean ->
            ChartCard(
                state = state,
                windowStart = windowStart,
                windowEnd = windowEnd,
                enabledCategories = enabledCategories,
                legendCategories = legendCategories,
                onToggleCategory = onToggleCategory,
                onEventClick = { editing = EventSheetTarget.Edit(it) },
                chartHeight = chartHeight,
                range = range,
                onRange = { range = it },
                modifier = mod,
                showLegend = showLegend,
            )
        }
        val listCard = @Composable { mod: Modifier, allowMaximize: Boolean ->
            Card(
                mod,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                ReadingEventList(
                    mode = listMode,
                    onMode = { listMode = it },
                    history = state.history,
                    events = state.events,
                    unit = state.unit,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    onReadingClick = { readingDetail = it },
                    onEventClick = { editing = EventSheetTarget.Edit(it) },
                    readingDisplay = state.readingDisplay,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    // The speed-dial FAB floats over the list's bottom rows in every layout;
                    // pad the scrolled content so the last rows can scroll clear of it
                    // (56dp FAB + 20dp screen margin + breathing room).
                    bottomPadding = 88,
                    maximized = isMaximized,
                    onToggleMaximize = if (allowMaximize) ({ maximized = !maximized }) else null,
                )
            }
        }

        when {
            isMaximized -> {
                // List-only sub-view; back restores the graph.
                BackHandler { maximized = false }
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    // Same status surface as the other layouts (warm-up / setup-failure / fault),
                    // not just the raw error — keeps the maximized view consistent.
                    SensorStatusBanner(state.sensor, hasReading = state.reading != null)
                    listCard(Modifier.fillMaxSize().padding(vertical = 8.dp), true)
                }
            }
            landscape -> {
                // Two panes: a compact status header + tall graph on the left, the scrolling list
                // on the right. The graph height is derived from the pane height (reserving room
                // for the compact header, legend and range picker) — a fixed height renders
                // reliably where a weighted BoxWithConstraints chart collapses. The left pane can
                // scroll if a short screen can't fit it all.
                val density = androidx.compose.ui.platform.LocalDensity.current
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    SensorStatusBanner(state.sensor, hasReading = state.reading != null)
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // The left pane scrolls; the chart is sized to exactly fill the viewport
                        // below the (variable-height) header so its x-axis lands at the fold, with
                        // the range picker just out of view (scroll to reveal). The legend is hidden.
                        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                            val paneH = maxHeight
                            var headerDp by remember { mutableStateOf(0.dp) }
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                Box(Modifier.onSizeChanged { headerDp = with(density) { it.height.toDp() } }) {
                                    header(true)
                                }
                                Spacer(Modifier.height(8.dp))
                                val chartH = (paneH - headerDp - 8.dp).coerceAtLeast(140.dp)
                                chartCard(Modifier.fillMaxWidth(), chartH, false)
                            }
                        }
                        listCard(Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp), false)
                    }
                }
            }
            else -> {
                // Fixed status + graph; only the list scrolls.
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    SensorStatusBanner(state.sensor, hasReading = state.reading != null)
                    header(false)
                    Spacer(Modifier.height(10.dp))
                    chartCard(Modifier.fillMaxWidth(), 232.dp, true)
                    Spacer(Modifier.height(10.dp))
                    // Bottom margin after the weight so the card stops short of the navigation
                    // bar, matching the maximized and landscape layouts.
                    listCard(Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp), true)
                }
            }
        }

        AddEventFab(
            types = displayTypes,
            onPick = { editing = EventSheetTarget.New(it) },
        )
    }

    EventSheets(
        editing = editing,
        types = displayTypes,
        onDismiss = { editing = null },
        onLog = { type, value, at, note -> viewModel.logEvent(type, value, at, note) },
        onUpdate = { original, edited -> viewModel.updateEvent(original, edited) },
        onDelete = { viewModel.deleteEvent(it) },
    )

    readingDetail?.let { ReadingDetailSheet(it, state.unit, now) { readingDetail = null } }
}

/** Hosts the new/edit event sheet for the given target. Shared by Home and the day detail. */
@Composable
fun EventSheets(
    editing: EventSheetTarget?,
    types: List<de.mogluco.data.EventType>,
    onDismiss: () -> Unit,
    onLog: (de.mogluco.data.EventType, Float, Long, String) -> Unit,
    onUpdate: (original: LoggedEvent, edited: LoggedEvent) -> Unit,
    onDelete: (LoggedEvent) -> Unit,
) {
    when (editing) {
        is EventSheetTarget.New -> EventEditSheet(
            types = types,
            initialType = editing.type,
            existing = null,
            onDismiss = onDismiss,
            onSave = { type, value, at, note -> onLog(type, value, at, note) },
        )
        is EventSheetTarget.Edit -> EventEditSheet(
            types = types,
            initialType = null,
            existing = editing.event,
            onDismiss = onDismiss,
            // Pass the unmodified original as the delete key alongside the edited copy, so the
            // engine deletes the right row instead of appending a duplicate (see EventRepository).
            onSave = { type, value, at, note ->
                onUpdate(
                    editing.event,
                    editing.event.copy(
                        epochMillis = at, type = type.index, value = value, note = note,
                        category = type.category, label = type.name,
                    ),
                )
            },
            onDelete = onDelete,
        )
        null -> Unit
    }
}

sealed interface EventSheetTarget {
    data class New(val type: de.mogluco.data.EventType) : EventSheetTarget
    data class Edit(val event: LoggedEvent) : EventSheetTarget

    companion object {
        /**
         * Flattens the target to Bundle-able primitives so an open sheet survives configuration
         * changes. The Edit case restores the exact original (time, type, value) tuple — that
         * matters because the engine uses it as the delete key on update (see [EventSheets]).
         * A null target saves as an empty list, which listSaver drops → restore yields null.
         */
        val Saver: Saver<EventSheetTarget?, Any> = listSaver(
            save = { target ->
                when (target) {
                    null -> emptyList()
                    is New -> listOf("new", target.type.index, target.type.name, target.type.category.name)
                    is Edit -> target.event.let {
                        listOf("edit", it.epochMillis, it.type, it.value, it.note, it.category.name, it.label)
                    }
                }
            },
            restore = { l ->
                when (l.firstOrNull()) {
                    "new" -> New(
                        de.mogluco.data.EventType(
                            index = l[1] as Int,
                            name = l[2] as String,
                            category = EventCategory.valueOf(l[3] as String),
                        ),
                    )
                    "edit" -> Edit(
                        LoggedEvent(
                            epochMillis = l[1] as Long,
                            type = l[2] as Int,
                            value = l[3] as Float,
                            note = l[4] as String,
                            category = EventCategory.valueOf(l[5] as String),
                            label = l[6] as String,
                        ),
                    )
                    else -> null
                }
            },
        )
    }
}

/**
 * Saves the legend's enabled categories by name (a Set of enums isn't Bundle-able as-is).
 * Joined into a single string rather than a listSaver so an all-disabled (empty) set still
 * round-trips — listSaver drops empty lists entirely.
 */
private val EnabledCategoriesSaver: Saver<Set<EventCategory>, String> = Saver(
    save = { set -> set.joinToString(",") { it.name } },
    restore = { s ->
        if (s.isEmpty()) emptySet()
        else s.split(',').mapNotNull { n -> EventCategory.entries.firstOrNull { it.name == n } }.toSet()
    },
)

/** Keeps the reading-detail sheet (and the reading it shows) alive across rotation. */
private val ReadingDetailSaver: Saver<GlucoseReading?, Any> = listSaver(
    save = { r ->
        if (r == null) emptyList()
        else listOf(r.calibratedMgdl, r.rawMgdl, r.epochMillis, r.trend.name, r.ratePerMin)
    },
    restore = { l ->
        GlucoseReading(
            calibratedMgdl = l[0] as Double,
            rawMgdl = l[1] as Double,
            epochMillis = l[2] as Long,
            trend = TrendArrow.valueOf(l[3] as String),
            ratePerMin = l[4] as Double,
        )
    },
)

// --- Header + chart card (extracted so portrait + landscape share them) ---

@Composable
private fun HeaderCards(
    state: HomeUiState,
    now: Long,
    onAddSensor: () -> Unit,
    onOpenSensorInfo: () -> Unit,
    compact: Boolean = false,
) {
    // The left (reading) card has an exaggerated top-left corner; the right (sensor) card an
    // exaggerated bottom-right corner — together they frame the status area.
    val big = 28.dp
    val small = 12.dp
    val leftShape = RoundedCornerShape(topStart = big, topEnd = small, bottomEnd = small, bottomStart = small)
    val rightShape = RoundedCornerShape(topStart = small, topEnd = small, bottomEnd = big, bottomStart = small)

    Row(
        // IntrinsicSize.Min keeps the two status cards equal-height (so the "No sensor / Add
        // sensor" card never towers over the sensor card). Safe in landscape now that the graph
        // uses a fixed height rather than a weight that intrinsic measurement would collapse.
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReadingCard(
            reading = state.reading,
            sensor = state.sensor,
            unit = state.unit,
            targetLow = state.targetLow,
            targetHigh = state.targetHigh,
            now = now,
            onAddSensor = onAddSensor,
            shape = leftShape,
            compact = compact,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        SensorCard(
            reading = state.reading,
            sensor = state.sensor,
            now = now,
            onClick = onOpenSensorInfo,
            shape = rightShape,
            compact = compact,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun ChartCard(
    state: HomeUiState,
    windowStart: Long,
    windowEnd: Long,
    enabledCategories: Set<EventCategory>,
    legendCategories: List<EventCategory>,
    onToggleCategory: (EventCategory) -> Unit,
    onEventClick: (LoggedEvent) -> Unit,
    chartHeight: Dp?,
    range: TimeRange,
    onRange: (TimeRange) -> Unit,
    modifier: Modifier = Modifier,
    showLegend: Boolean = true,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        // chartHeight == null → the chart fills the card's remaining height (landscape, where the
        // card is weighted to fill the pane); otherwise it's a fixed height (portrait).
        val fill = chartHeight == null
        Column(Modifier.padding(vertical = 8.dp).then(if (fill) Modifier.fillMaxHeight() else Modifier)) {
            // The legend is hidden in landscape (no room); the previously-selected categories
            // still drive which events the chart draws.
            if (showLegend) {
                EventLegend(enabled = enabledCategories, onToggle = onToggleCategory, categories = legendCategories)
            }
            val chart = @Composable { mod: Modifier ->
                GlucoseChart(
                    points = state.history,
                    events = state.events,
                    unit = state.unit,
                    targetLow = state.targetLow,
                    targetHigh = state.targetHigh,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    readingDisplay = state.readingDisplay,
                    enabledEventCategories = enabledCategories,
                    showTargetBand = state.showTargetBand,
                    gapThresholdMillis = state.gapThresholdMillis,
                    onEventClick = onEventClick,
                    modifier = mod,
                    resetKey = range,
                )
            }
            if (fill) {
                // Weight the wrapping Box (not the chart's SubcomposeLayout directly) so the chart
                // gets a concrete measured height; weighting BoxWithConstraints directly collapses it.
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    chart(Modifier.fillMaxSize().padding(horizontal = 4.dp))
                }
            } else {
                chart(Modifier.fillMaxWidth().height(chartHeight!!).padding(horizontal = 4.dp))
            }
            // Time-range picker sits below the chart, inside the card.
            Spacer(Modifier.height(8.dp))
            RangeSelector(range = range, onRange = onRange, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

// --- Error banner ---

@Composable
private fun ErrorBanner(error: de.mogluco.data.SensorError) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(
                    "${error.title} (code ${error.code})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    error.meaning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/** Grace after warm-up before a sensor that still has no readings is treated as stuck. */
private const val STUCK_GRACE_MS = 5 * 60_000L

/**
 * Sensor status strip below the header. Shows, in priority order: a reported fault (error
 * banner), else a warm-up countdown, else — once warm-up has elapsed with grace and still no
 * reading — a "setup didn't complete" banner. That last case is the time-based safety net for a
 * Libre 3 handshake that silently never streams (upstream issue #405); the engine-driven
 * connection error covers the case where the failure produced a BLE disconnect.
 *
 * @param hasReading true once any reading has arrived (the sensor is streaming).
 */
@Composable
private fun SensorStatusBanner(sensor: de.mogluco.data.SensorInfo?, hasReading: Boolean) {
    if (sensor == null) return
    sensor.error?.let {
        ErrorBanner(it)
        return
    }
    val warmupEnd = sensor.warmupEndMillis()
    val stuckAt = warmupEnd + STUCK_GRACE_MS
    // Self-tick until data flows or we've decided it's stuck, then stop. Lifecycle-gated so a
    // backgrounded (or pager-retained, off-viewport) screen doesn't keep the countdown ticking;
    // returning to STARTED re-runs the block and re-evaluates immediately.
    val lifecycleOwner = LocalLifecycleOwner.current
    val now by produceState(initialValue = System.currentTimeMillis(), warmupEnd, hasReading, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (!hasReading && System.currentTimeMillis() < stuckAt) {
                value = System.currentTimeMillis()
                kotlinx.coroutines.delay(30_000)
            }
            value = System.currentTimeMillis()
        }
    }
    when {
        hasReading -> Unit // streaming — nothing to show
        now < warmupEnd -> WarmupBanner(((warmupEnd - now) / 60_000L).toInt() + 1)
        now >= stuckAt -> StuckSetupBanner()
        else -> Unit // warmed up moments ago — give the first reading a brief grace
    }
}

/** Warm-up countdown for a freshly-activated sensor (no readings yet). */
@Composable
private fun WarmupBanner(minutesRemaining: Int) {
    NoteSurface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        icon = Icons.Filled.Timelapse,
        title = "Sensor warming up",
        body = "Ready in about $minutesRemaining min — no readings until then.",
    )
}

/**
 * Shown when a sensor finished warm-up but has produced no readings — the Libre 3 handshake
 * never completed (upstream issue #405). Tells the user what to try so a silent failure can't
 * masquerade as an endless warm-up.
 */
@Composable
private fun StuckSetupBanner() {
    val context = LocalContext.current
    NoteSurface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        icon = Icons.Filled.WarningAmber,
        title = "No data after warm-up",
        body = "The sensor connected but never started sending glucose data — the Libre 3 setup " +
            "didn't complete. Try turning Bluetooth off and on, reboot, and make sure no other " +
            "app or phone is connected to this sensor.",
        action = {
            // The advice starts with "toggle Bluetooth" — take the user straight there rather
            // than leaving them to dig through system settings. runCatching: a device without
            // a Bluetooth settings activity shouldn't crash a status banner.
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }) { Text("Bluetooth settings") }
        },
    )
}

/** Shared layout for the warm-up / stuck status notes. */
@Composable
private fun NoteSurface(
    color: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = color,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = contentColor)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                )
                action?.invoke()
            }
        }
    }
}

// --- Header cards ---

@Composable
private fun ReadingCard(
    reading: GlucoseReading?,
    sensor: SensorInfo?,
    unit: GlucoseUnit,
    targetLow: Double,
    targetHigh: Double,
    now: Long,
    onAddSensor: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val glucose = MoglucoTheme.glucose
    val stateColor = reading?.let {
        if (it.isStale(now)) glucose.stale else glucose.colorFor(it.state(targetLow, targetHigh))
    } ?: glucose.stale

    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = HeaderContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(if (compact) 12.dp else 16.dp)) {
            if (reading == null) {
                if (sensor != null) {
                    // Sensor activated but not streaming yet (BLE handshake in progress after a
                    // scan/take-over). Show that it's coming up so the user doesn't rescan.
                    Text(
                        if (sensor.isWarmingUp(now)) "Warming up" else "Connecting…",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Waiting for the first reading — no need to rescan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("No sensor", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    AssistChip(onClick = onAddSensor, label = { Text("Add sensor") })
                }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    unit.format(reading.calibratedMgdl),
                    // Smaller number in compact (landscape) mode so the graph gets the height.
                    style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = stateColor,
                )
                Spacer(Modifier.width(4.dp))
                TrendArrow(reading = reading, tint = stateColor, size = if (compact) 24.dp else 32.dp)
                if (compact) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        unit.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!compact) {
                Text(
                    unit.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (reading.isCalibrated) {
                    Text(
                        "raw ${unit.format(reading.rawMgdl)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SensorCard(
    reading: GlucoseReading?,
    sensor: SensorInfo?,
    now: Long,
    onClick: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    // The onClick overload (not Modifier.clickable) so the ripple is clipped to the card's
    // rounded shape — same reasoning as EventRow's Surface(onClick).
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = HeaderContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxHeight().padding(if (compact) 12.dp else 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Timelapse,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    reading?.let { freshnessLabel(it, now) } ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "Sensor details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            if (sensor != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Sensors,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${sensor.dayOfLife(now)} / ${sensor.lifetimeDays}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    sensor.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "No sensor",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// --- Range selector ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSelector(range: TimeRange, onRange: (TimeRange) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        TimeRange.entries.forEachIndexed { i, r ->
            SegmentedButton(
                selected = r == range,
                onClick = { onRange(r) },
                shape = SegmentedButtonDefaults.itemShape(i, TimeRange.entries.size),
            ) {
                // All segments must fit on narrow phones: never wrap or truncate, and use a
                // slightly smaller label so "Yesterday" fits instead of shortening it to a cryptic
                // abbreviation.
                Text(r.label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}


// --- Reading detail sheet ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingDetailSheet(reading: GlucoseReading, unit: GlucoseUnit, now: Long, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Reading", style = MaterialTheme.typography.headlineSmall)
            Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(reading.epochMillis)))
            Text("Smoothed: ${unit.format(reading.calibratedMgdl)} ${unit.label}")
            Text("Raw: ${unit.format(reading.rawMgdl)} ${unit.label}")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Trend: ${reading.trend.name.lowercase().replace('_', ' ')} (${rateLabel(reading, unit)})")
                Spacer(Modifier.width(8.dp))
                TrendArrow(reading = reading, tint = MaterialTheme.colorScheme.onSurface, size = 22.dp)
            }
        }
    }
}

// --- helpers ---

/** A subtly tinted container so the status cards stand out from the page background. */
private val HeaderContainer: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant

/** Signed rate of change in the display unit per minute, e.g. "+1.2 mg/dL/min". */
private fun rateLabel(reading: GlucoseReading, unit: GlucoseUnit): String {
    val rate = unit.fromMgdl(reading.ratePerMin)
    val sign = if (rate >= 0) "+" else ""
    val v = when (unit) {
        GlucoseUnit.MGDL -> String.format("%.1f", rate)
        GlucoseUnit.MMOLL -> String.format("%.2f", rate)
    }
    return "$sign$v ${unit.label}/min"
}

fun EventCategory.displayName(): String = when (this) {
    EventCategory.CARBS -> "Carbs"
    EventCategory.INSULIN -> "Insulin"
    EventCategory.ACTIVITY -> "Activity"
    EventCategory.BLOOD -> "Blood"
    EventCategory.OTHER -> "Other"
}

fun eventValueLabel(e: LoggedEvent): String {
    val u = e.category.unit
    val v = if (e.value == e.value.toLong().toFloat()) e.value.toLong().toString() else e.value.toString()
    return if (u.isEmpty()) v else "$v $u"
}

private fun freshnessLabel(reading: GlucoseReading, now: Long): String {
    val mins = (reading.ageMillis(now) / 60_000).toInt()
    return when {
        reading.isStale(now) -> "Stale · $mins min"
        mins <= 0 -> "Just now"
        mins == 1 -> "1 min ago"
        else -> "$mins min ago"
    }
}
