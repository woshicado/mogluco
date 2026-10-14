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
package de.mogluco.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.mogluco.data.EventCategory
import de.mogluco.ui.util.copyToClipboard
import de.mogluco.ui.util.localDayToUtc
import de.mogluco.ui.util.nextLocalMidnight
import de.mogluco.ui.util.startOfDay
import de.mogluco.ui.util.utcToLocalDay
import de.mogluco.data.GlucoseReading
import de.mogluco.data.SensorInfo
import de.mogluco.ui.export.ExportSheet
import de.mogluco.ui.home.BottomListMode
import de.mogluco.ui.home.EventSheetTarget
import de.mogluco.ui.home.EventSheets
import de.mogluco.ui.home.GlucoseChart
import de.mogluco.ui.home.eventColor
import de.mogluco.ui.home.HomeViewModel
import de.mogluco.ui.home.ReadingDetailSheet
import de.mogluco.ui.home.ReadingEventList
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Month calendar. Today and every known sensor's activation day (green ring) and end day (red
 * ring) are highlighted — a day on which one sensor ended and the next started shows both, as a
 * ring that is half red, half green. Tapping a day shows that day's readings + events inline
 * (with Home's interactions). The header always reflects the sensor that was active on the selected day
 * (or says there was none, without shifting the layout). The range-select toggle turns days
 * into a start→end selection and reveals a floating export button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(viewModel: HomeViewModel, onOpenDay: (Long) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Saveable (they're plain Longs) so rotation / process death doesn't snap the calendar
    // back to today, losing the month/day the user had navigated to.
    var monthAnchor by rememberSaveable { mutableStateOf(startOfMonth(System.currentTimeMillis())) }
    var selectedDay by rememberSaveable { mutableStateOf(startOfDay(System.currentTimeMillis())) }
    // The range selection and its export sheet are saveable too: rotating while the system file
    // picker was open dropped the sheet, so the picked file's result was never delivered and an
    // empty file was left behind without a message.
    var rangeMode by rememberSaveable { mutableStateOf(false) }
    var rangeStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var rangeEnd by rememberSaveable { mutableStateOf<Long?>(null) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showMonthPicker by remember { mutableStateOf(false) }
    // Survives configuration changes like on Home, so a rotation mid-entry keeps the sheet open.
    var editing by rememberSaveable(stateSaver = EventSheetTarget.Saver) {
        mutableStateOf<EventSheetTarget?>(null)
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val selectedSensor = remember(selectedDay, state.sensor, state.sensorHistory) {
        sensorOnDay(state.sensor, state.sensorHistory, selectedDay)
    }
    // Marker days for every stored sensor (past + current), start-of-day millis.
    val activationDays = remember(state.sensor, state.sensorHistory) {
        knownSensors(state.sensor, state.sensorHistory).map { startOfDay(it.activationMillis) }.toSet()
    }
    val expiryDays = remember(state.sensor, state.sensorHistory) {
        knownSensors(state.sensor, state.sensorHistory).map { startOfDay(it.expiryMillis()) }.toSet()
    }
    // Per-day event categories → colored dots under the day number. Bucketed once per change of
    // the event list (reusing a single Calendar so a year of events stays cheap). Activity is
    // omitted when its display is turned off, matching the rest of the UI.
    val eventDays = remember(state.events, state.enabledCategories) {
        val cal = Calendar.getInstance()
        val map = HashMap<Long, MutableSet<EventCategory>>()
        state.events.forEach { e ->
            if (e.category !in state.enabledCategories) return@forEach
            cal.timeInMillis = e.epochMillis
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            map.getOrPut(cal.timeInMillis) { mutableSetOf() }.add(e.category)
        }
        map
    }

    fun onDayTap(day: Long) {
        if (rangeMode) {
            val s = rangeStart
            val e = rangeEnd
            when {
                s == null || (s != null && e != null) -> { rangeStart = day; rangeEnd = null }
                day < s -> { rangeStart = day; rangeEnd = s }
                else -> rangeEnd = day
            }
        } else {
            selectedDay = day
        }
    }

    fun jumpToToday() {
        val today = startOfDay(System.currentTimeMillis())
        monthAnchor = startOfMonth(today)
        selectedDay = today
    }

    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        val landscape = maxWidth > maxHeight

        val sensorPanel = @Composable {
            SensorPanel(selectedSensor, selectedDay, onCopy = { sensor ->
                val expired = sensor.expiryMillis() <= System.currentTimeMillis()
                val text = "Sensor ${sensor.id}\nActivated ${dayLabel(sensor.activationMillis)}\n" +
                    "${if (expired) "Expired" else "Expires"} ${dayLabel(sensor.expiryMillis())}"
                copyToClipboard(context, "Sensor", text)
            })
        }

        // Month header with range-select, prev / today / next.
        val monthHeader = @Composable {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    monthLabel(monthAnchor),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    // Tap the month/year to jump anywhere via a date picker.
                    modifier = Modifier.weight(1f).clip(MaterialTheme.shapes.small)
                        .clickable { showMonthPicker = true }.padding(vertical = 4.dp),
                )
                FilledTonalIconButton(
                    onClick = {
                        rangeMode = !rangeMode
                        rangeStart = null; rangeEnd = null
                    },
                    colors = if (rangeMode) {
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        IconButtonDefaults.filledTonalIconButtonColors()
                    },
                ) { Icon(Icons.Rounded.SelectAll, contentDescription = "Select range") }
                IconButton(onClick = { monthAnchor = addMonths(monthAnchor, -1) }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Previous month")
                }
                IconButton(onClick = { jumpToToday() }) {
                    Icon(Icons.Rounded.Today, contentDescription = "Jump to today")
                }
                IconButton(onClick = { monthAnchor = addMonths(monthAnchor, 1) }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Next month")
                }
            }
        }

        val grid = @Composable {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                MonthGrid(
                    monthAnchor = monthAnchor,
                    selectedDay = if (rangeMode) null else selectedDay,
                    rangeStart = rangeStart,
                    rangeEnd = rangeEnd,
                    activationDays = activationDays,
                    expiryDays = expiryDays,
                    eventMarkers = eventDays,
                    onSelect = ::onDayTap,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }

        val daySelector = @Composable {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(java.util.Date(selectedDay)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onOpenDay(selectedDay) }) {
                    Icon(Icons.Rounded.ZoomIn, contentDescription = "Open full day view")
                }
            }
        }

        // The day graph; the full readings/events lists live in the dedicated day-detail view.
        val dayGraph = @Composable { mod: Modifier ->
            Card(
                mod,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                GlucoseChart(
                    points = state.history,
                    events = state.events,
                    unit = state.unit,
                    targetLow = state.targetLow,
                    targetHigh = state.targetHigh,
                    windowStart = selectedDay,
                    // Half-open [midnight, next midnight): DST-safe (a fall-back day keeps its
                    // 25th hour) and −1 keeps a reading exactly on the boundary out of both
                    // neighbouring days (the chart/list windows are end-inclusive).
                    windowEnd = nextLocalMidnight(selectedDay) - 1,
                    // Clear the tap-to-inspect line when the selected day changes — every normal day
                    // has the same duration, so the chart can't reset on duration alone.
                    resetKey = selectedDay,
                    readingDisplay = state.readingDisplay,
                    enabledEventCategories = EventCategory.entries.toSet(),
                    showTargetBand = state.showTargetBand,
                    gapThresholdMillis = state.gapThresholdMillis,
                    onEventClick = { editing = EventSheetTarget.Edit(it) },
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            }
        }

        if (landscape) {
            // LEFT: sensor info above the day graph; the graph fills the pane's remaining height
            // (measured, so it's as tall as possible). RIGHT: the calendar — month header + a grid
            // that stretches to fill the pane exactly (no scroll). Both panes are the same height.
            val density = androidx.compose.ui.platform.LocalDensity.current
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val paneH = maxHeight
                    var topDp by remember { mutableStateOf(0.dp) }
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.onSizeChanged { topDp = with(density) { it.height.toDp() } }) {
                            Column {
                                sensorPanel()
                                if (!rangeMode) daySelector()
                            }
                        }
                        if (rangeMode) {
                            Spacer(Modifier.height(8.dp))
                            RangeHint(rangeStart, rangeEnd)
                        } else {
                            val graphH = (paneH - topDp - 8.dp).coerceAtLeast(120.dp)
                            dayGraph(Modifier.fillMaxWidth().height(graphH).padding(bottom = 8.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    monthHeader()
                    Card(
                        Modifier.fillMaxWidth().weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        MonthGrid(
                            monthAnchor = monthAnchor,
                            selectedDay = if (rangeMode) null else selectedDay,
                            rangeStart = rangeStart,
                            rangeEnd = rangeEnd,
                            activationDays = activationDays,
                            expiryDays = expiryDays,
                            eventMarkers = eventDays,
                            onSelect = ::onDayTap,
                            fillHeight = true,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                sensorPanel()
                monthHeader()
                grid()
                Spacer(Modifier.height(8.dp))
                if (rangeMode) {
                    RangeHint(rangeStart, rangeEnd)
                } else {
                    daySelector()
                    dayGraph(Modifier.fillMaxWidth().weight(1f))
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        if (rangeMode && rangeStart != null && rangeEnd != null) {
            ExtendedFloatingActionButton(
                onClick = { showExport = true },
                icon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                text = { Text("Export range") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            )
        }
    }

    if (showExport) {
        val s = rangeStart
        val e = rangeEnd
        if (s != null && e != null) {
            val lo = minOf(s, e)
            // Half-open [lo, midnight after the end day): DST-safe, and −1 stops a reading at
            // exactly the next midnight from leaking in (the export filter is end-inclusive).
            val hi = nextLocalMidnight(maxOf(s, e)) - 1
            // Not state.history / state.events: the export reads the repositories itself when it
            // writes, and takes the unfiltered log — state.events drops the kinds turned off in
            // Settings → Event logging (a display filter), which must not remove them from an export.
            ExportSheet(
                loadData = viewModel::exportData,
                fixedWindow = lo..hi,
                allowFullBackup = false,
                onDismiss = { showExport = false },
                title = "Export selected range",
            )
        }
    }

    if (showMonthPicker) {
        // Material date picker (with its year grid) to jump the calendar to any month/year quickly.
        // The picker works in UTC; convert to/from the local calendar so the day doesn't shift.
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = localDayToUtc(selectedDay))
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showMonthPicker = false },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { utc ->
                        val day = utcToLocalDay(utc)
                        monthAnchor = startOfMonth(day)
                        selectedDay = day
                    }
                    showMonthPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showMonthPicker = false }) { Text("Cancel") }
            },
        ) {
            androidx.compose.material3.DatePicker(state = pickerState)
        }
    }

    EventSheets(
        editing = editing,
        types = remember(state.enabledLogKinds) { viewModel.displayTypes(state.enabledLogKinds) },
        onDismiss = { editing = null },
        onLog = { type, value, at, note -> viewModel.logEvent(type, value, at, note) },
        onUpdate = { original, edited -> viewModel.updateEvent(original, edited) },
        onDelete = { viewModel.deleteEvent(it) },
    )
}

@Composable
private fun SensorPanel(sensor: SensorInfo?, selectedDay: Long, onCopy: (SensorInfo) -> Unit) {
    // Subtle background so it recedes behind the calendar (not the page's focus).
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 64.dp)
            .then(if (sensor != null) Modifier.clickable { onCopy(sensor) } else Modifier),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sensor != null) {
                val now = System.currentTimeMillis()
                val expired = sensor.expiryMillis() <= now
                Column(Modifier.weight(1f)) {
                    Text("Sensor ${sensor.id}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Activated ${dayLabel(sensor.activationMillis)} · " +
                            (if (expired) "expired " else "expires ") + dayLabel(sensor.expiryMillis()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Hidden outside the sensor's life — dayOfLife() kept counting past expiry
                // ("Day 44 / 14" when selecting a later day).
                sensorDayNumber(sensor, selectedDay)?.let { dayN ->
                    Text("Day $dayN / ${sensor.lifetimeDays}", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.size(10.dp))
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy sensor info",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(Modifier.weight(1f)) {
                    Text("No sensor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "No sensor was active on this date.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RangeHint(start: Long?, end: Long?) {
    val text = when {
        start == null -> "Tap a start day, then an end day to export a range."
        end == null -> "Start: ${dayLabel(start)} — now tap an end day."
        else -> "Range: ${dayLabel(minOf(start, end))} – ${dayLabel(maxOf(start, end))}"
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.DateRange, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.size(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun MonthGrid(
    monthAnchor: Long,
    selectedDay: Long?,
    rangeStart: Long?,
    rangeEnd: Long?,
    activationDays: Set<Long>,
    expiryDays: Set<Long>,
    eventMarkers: Map<Long, Set<EventCategory>>,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
) {
    val weekdays = remember { arrayOf("M", "T", "W", "T", "F", "S", "S") }
    // A plain remember{} goes stale past midnight (the "today" ring stayed on yesterday if the
    // screen was left open): sleep until the next local midnight, then roll the value over.
    val today by produceState(initialValue = startOfDay(System.currentTimeMillis())) {
        while (true) {
            kotlinx.coroutines.delay(
                (nextLocalMidnight(value) - System.currentTimeMillis()).coerceAtLeast(1_000L),
            )
            value = startOfDay(System.currentTimeMillis())
        }
    }
    val cells = remember(monthAnchor) { monthCells(monthAnchor) }
    val rLo = if (rangeStart != null && rangeEnd != null) minOf(rangeStart, rangeEnd) else rangeStart
    val rHi = if (rangeStart != null && rangeEnd != null) maxOf(rangeStart, rangeEnd) else rangeStart

    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            weekdays.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        // fillHeight (landscape) → the week rows share the remaining height so the grid fills the
        // pane exactly (taller cells, no scrolling). Otherwise each row is its natural height.
        cells.chunked(7).forEach { week ->
            Row(
                Modifier.fillMaxWidth().then(if (fillHeight) Modifier.weight(1f) else Modifier),
            ) {
                week.forEach { day ->
                    DayCell(
                        day = day,
                        inMonth = day != null && isSameMonth(day, monthAnchor),
                        isToday = day == today,
                        isSelected = day != null && day == selectedDay,
                        inRange = day != null && rLo != null && rHi != null && day in rLo..rHi,
                        isActivation = day != null && day in activationDays,
                        isExpiry = day != null && day in expiryDays,
                        markers = day?.let { eventMarkers[it] }.orEmpty(),
                        onClick = { day?.let(onSelect) },
                        fillHeight = fillHeight,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Long?,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    inRange: Boolean,
    isActivation: Boolean,
    isExpiry: Boolean,
    markers: Set<EventCategory>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
) {
    // Fixed (shorter than wide) row height to keep the calendar compact, with a circular
    // highlight of fixed size centered in the cell. In fillHeight mode the cell fills the
    // weighted row instead (landscape: the grid stretches to fill the pane).
    val cellMod = if (fillHeight) Modifier.fillMaxHeight() else Modifier.height(44.dp)
    Box(modifier.then(cellMod).clickable(enabled = day != null, onClick = onClick), contentAlignment = Alignment.Center) {
        if (day != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .then(
                            when {
                                isToday -> Modifier.background(MaterialTheme.colorScheme.primary)
                                isSelected -> Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                                inRange -> Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                                // A replacement day: the old sensor's end and the new one's
                                // start. Both markers show, in reading order — the end (red) on
                                // the left half of the ring, the start (green) on the right.
                                // With only the green ring, a past sensor's end was rarely marked.
                                isActivation && isExpiry -> Modifier.border(
                                    2.dp,
                                    Brush.horizontalGradient(
                                        0f to MaterialTheme.colorScheme.error,
                                        0.5f to MaterialTheme.colorScheme.error,
                                        0.5f to ActivationRing,
                                        1f to ActivationRing,
                                    ),
                                    CircleShape,
                                )
                                isActivation -> Modifier.border(2.dp, ActivationRing, CircleShape)
                                isExpiry -> Modifier.border(2.dp, MaterialTheme.colorScheme.error, CircleShape)
                                else -> Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        dayOfMonth(day).toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            isToday -> MaterialTheme.colorScheme.onPrimary
                            isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                            inRange -> MaterialTheme.colorScheme.onSecondaryContainer
                            !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                // Event dots — a fixed-height row reserved on every cell so days with and without
                // events keep their number at the same baseline. One dot per logged category.
                Spacer(Modifier.height(2.dp))
                Row(
                    Modifier.height(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MARKER_ORDER.forEach { cat ->
                        if (cat in markers) {
                            Box(Modifier.size(5.dp).clip(CircleShape).background(eventColor(cat)))
                        }
                    }
                }
            }
        }
    }
}

private val ActivationRing = androidx.compose.ui.graphics.Color(0xFF22C55E)

/** Fixed left→right order for the per-day event dots, so the colors are stable across days. */
private val MARKER_ORDER = listOf(
    EventCategory.BLOOD, EventCategory.INSULIN, EventCategory.CARBS, EventCategory.ACTIVITY,
)

// --- date helpers ---

/**
 * The current sensor merged into the stored sensor history (the current one wins for its id: it
 * carries the live error state), oldest activation first.
 *
 * Each sensor's life is bounded by what the store knows: a sensor followed by a later one ended at
 * its last stored reading ([SensorInfo.endMillis]; its nominal expiry when the store has none) and
 * never after the next sensor's activation — a sensor that failed or was replaced early doesn't
 * claim the days after it. The current sensor and the newest stored one (possibly still worn, so
 * its last reading is no end) keep their nominal life.
 */
internal fun knownSensors(current: SensorInfo?, history: List<SensorInfo>): List<SensorInfo> {
    val others = history.filter { it.id != current?.id }
    val sorted = (others + listOfNotNull(current)).sortedBy { it.activationMillis }
    return sorted.mapIndexed { i, sensor ->
        val next = sorted.getOrNull(i + 1)
        if (next == null || sensor.id == current?.id) {
            sensor.copy(endMillis = null)
        } else {
            sensor.copy(endMillis = minOf(sensor.expiryMillis(), next.activationMillis))
        }
    }
}

/**
 * The sensor that was active on [dayStartMillis]. Looks through every stored sensor ([history],
 * plus the [current] one) for those whose life — activation day through last expiry day, bounded
 * as in [knownSensors] — covers the day, and picks the most recently activated (a new sensor
 * started before the old one expired takes over from its activation day). Falls back to the
 * current sensor for days after its expiry while it is still worn, as before. Null when no known
 * sensor covered the day.
 */
internal fun sensorOnDay(current: SensorInfo?, history: List<SensorInfo>, dayStartMillis: Long): SensorInfo? {
    val covering = knownSensors(current, history).lastOrNull { sensorDayNumber(it, dayStartMillis) != null }
    return covering ?: current?.takeIf { dayStartMillis >= startOfDay(it.activationMillis) }
}

/**
 * The 1-based sensor day badge for [dayStartMillis], or null when the day falls outside the
 * sensor's [activation day, last expiry day] window (dayOfLife() kept counting past expiry, so
 * adjacent days showed "Day 44 / 14"). The number itself comes from [SensorInfo.dayOfLife] —
 * local calendar days, activation date = day 1 — so the badge always matches Home, Settings and
 * the notification; the final partial day stays clamped to [SensorInfo.lifetimeDays].
 */
internal fun sensorDayNumber(sensor: SensorInfo, dayStartMillis: Long): Int? {
    val activationDay = startOfDay(sensor.activationMillis)
    // −1 ms so a sensor expiring exactly at midnight doesn't claim the following day too.
    val expiryDay = startOfDay(sensor.expiryMillis() - 1)
    if (dayStartMillis < activationDay || dayStartMillis > expiryDay) return null
    return sensor.dayOfLife(dayStartMillis).coerceAtMost(sensor.lifetimeDays)
}

private fun startOfMonth(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun addMonths(millis: Long, delta: Int): Long =
    Calendar.getInstance().apply { timeInMillis = millis; add(Calendar.MONTH, delta) }.timeInMillis

// localDayToUtc / utcToLocalDay (the Material-DatePicker UTC-midnight ↔ local-day bridge) and
// startOfDay/nextLocalMidnight now live in de.mogluco.ui.util.DateUtils, shared with Settings.

/** 42 day-cells (6 weeks) starting on the Monday on/before the 1st of the anchored month. */
private fun monthCells(monthAnchor: Long): List<Long?> {
    val c = Calendar.getInstance().apply { timeInMillis = monthAnchor }
    val dow = c.get(Calendar.DAY_OF_WEEK) // Sunday=1..Saturday=7
    val backToMonday = ((dow + 5) % 7)
    c.add(Calendar.DAY_OF_MONTH, -backToMonday)
    return (0 until 42).map {
        val day = startOfDay(c.timeInMillis)
        c.add(Calendar.DAY_OF_MONTH, 1)
        day
    }
}

private fun isSameMonth(day: Long, monthAnchor: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = day }
    val b = Calendar.getInstance().apply { timeInMillis = monthAnchor }
    return a.get(Calendar.MONTH) == b.get(Calendar.MONTH) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
}

private fun dayOfMonth(day: Long): Int =
    Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.DAY_OF_MONTH)

private fun monthLabel(millis: Long): String =
    SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(java.util.Date(millis))

private fun dayLabel(millis: Long): String =
    SimpleDateFormat("d MMM", Locale.getDefault()).format(java.util.Date(millis))
