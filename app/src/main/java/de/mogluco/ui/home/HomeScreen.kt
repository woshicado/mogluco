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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Bluetooth
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
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
            // Up to the latest reading, not only to the last 30 s tick: a reading that arrives
            // between two ticks is stamped later than `now`, and the list and the chart would
            // show it up to half a minute after the value card above them.
            windowEnd = maxOf(now, state.reading?.epochMillis ?: 0L)
            windowStart = windowEnd - range.millis
        }
    }

    val onToggleCategory: (EventCategory) -> Unit = { cat ->
        enabledCategories = if (cat in enabledCategories) enabledCategories - cat else enabledCategories + cat
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
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
        // No maximize button here: in portrait the page scrolls the graph away to give the list
        // the space below the value cards, in landscape the list has a pane of its own.
        val listCard = @Composable { mod: Modifier ->
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
                )
            }
        }

        when {
            landscape -> {
                // Two panes: a compact status header + tall graph on the left, the scrolling list
                // on the right. The graph height is derived from the pane height (reserving room
                // for the compact header, legend and range picker) — a fixed height renders
                // reliably where a weighted BoxWithConstraints chart collapses. The left pane can
                // scroll if a short screen can't fit it all.
                val density = androidx.compose.ui.platform.LocalDensity.current
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    SensorStatusBanner(state.sensor, state.reading)
                    NewSensorFaultBanner(state.newSensorFault)
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
                        listCard(Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp))
                    }
                }
            }
            else -> {
                // The status banners and the value cards stay at the top; the graph and the list
                // below them scroll as one page that passes under them. Moving it up, the graph
                // goes first: the list card is as tall as the space below the cards, so with the
                // graph out of view it fills that space — its Readings / Events header in sight
                // just below the cards — and only its rows scroll on (GraphFirstScroll). Without
                // this, a small screen or a large font left the list a sliver below the graph.
                val density = LocalDensity.current
                // The pinned part's height, its fade included: where the page begins.
                var topHeight by remember { mutableStateOf(0.dp) }
                val pageScroll = rememberScrollState()
                val graphFirst = remember(pageScroll) { GraphFirstScroll(pageScroll) }
                val gap = 10.dp
                val background = MaterialTheme.colorScheme.background
                // The card stops short of the navigation bar by the same margin as the landscape
                // layout (the spacer after it).
                val listHeight = (maxHeight - topHeight - 8.dp).coerceAtLeast(MIN_LIST_HEIGHT)
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .nestedScroll(graphFirst)
                            .verticalScroll(pageScroll)
                            .padding(horizontal = 12.dp),
                    ) {
                        Spacer(Modifier.height(topHeight))
                        chartCard(Modifier.fillMaxWidth(), 232.dp, true)
                        Spacer(Modifier.height(gap))
                        listCard(Modifier.fillMaxWidth().height(listHeight))
                        Spacer(Modifier.height(8.dp))
                    }
                    // Drawn over the page: the graph passes under the cards, not through the
                    // space around and between them. Edge to edge, so the cards' shadows at the
                    // sides are covered as well; and into the gap below the cards it fades out,
                    // so what passes under them fades away rather than being cut off by a line.
                    // It also takes every touch that lands on it: the gaps around and between the
                    // cards are no pointer targets of their own, and a tap there went through to
                    // the graph's range buttons and chart hidden underneath.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { topHeight = with(density) { it.height.toDp() } }
                            .pointerInput(Unit) {},
                    ) {
                        Column(Modifier.fillMaxWidth().background(background).padding(horizontal = 12.dp)) {
                            SensorStatusBanner(state.sensor, state.reading)
                            NewSensorFaultBanner(state.newSensorFault)
                            header(false)
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(gap)
                                .background(Brush.verticalGradient(listOf(background, background.copy(alpha = 0f)))),
                        )
                    }
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

/**
 * The nested scrolling of Home's portrait page ([page]: the graph, then the list card, whose own
 * list scrolls inside it). Moving the content up, the page gives way first, so the graph leaves
 * before the list moves; moving it down, the list scrolls back to its top first and the page —
 * the graph — follows with what is left (the page's own handling of what the list leaves over).
 * Flings go the same way: their steps pass through here too.
 */
private class GraphFirstScroll(private val page: ScrollState) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y >= 0f || !page.canScrollForward) return Offset.Zero
        return Offset(0f, -page.dispatchRawDelta(-available.y))
    }
}

/** The least height of Home's list card, also where the space below the value cards is smaller. */
private val MIN_LIST_HEIGHT = 200.dp

/** Hosts the new/edit event sheet for the given target. Shared by Home and the day detail. */
@Composable
fun EventSheets(
    editing: EventSheetTarget?,
    types: List<de.mogluco.data.EventType>,
    onDismiss: () -> Unit,
    onLog: (de.mogluco.data.EventType, Float, Long, String) -> Unit,
    onUpdate: (original: LoggedEvent, edited: LoggedEvent) -> Unit,
    onDelete: (LoggedEvent) -> Unit,
    // The day a new event defaults to (the day-detail page's day); null = today.
    newEventDay: Long? = null,
) {
    when (editing) {
        is EventSheetTarget.New -> EventEditSheet(
            types = types,
            initialType = editing.type,
            existing = null,
            onDismiss = onDismiss,
            onSave = { type, value, at, note -> onLog(type, value, at, note) },
            newEventDay = newEventDay,
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
internal val EnabledCategoriesSaver: Saver<Set<EventCategory>, String> = Saver(
    save = { set -> set.joinToString(",") { it.name } },
    restore = { s ->
        if (s.isEmpty()) emptySet()
        else s.split(',').mapNotNull { n -> EventCategory.entries.firstOrNull { it.name == n } }.toSet()
    },
)

/** Keeps the reading-detail sheet (and the reading it shows) alive across rotation. */
internal val ReadingDetailSaver: Saver<GlucoseReading?, Any> = listSaver(
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

/** Grace before a sensor that should be streaming but still has no readings is treated as stuck. */
private const val STUCK_GRACE_MS = 5 * 60_000L

/**
 * Since when this process has been waiting for a sensor's first reading. Deliberately neither
 * composition- nor saved-state: a rotation must not restart the wait (the banner would vanish for
 * another [STUCK_GRACE_MS]), and a wait restored into a new process would be days old before that
 * process had tried at all. Main-thread (composition) use only.
 */
internal class FirstReadingWait {
    private var sensorId: String? = null
    private var since = 0L

    /** [now] when [sensorId] is first seen waiting; that same instant on every later call. */
    fun since(sensorId: String, now: Long): Long {
        if (sensorId != this.sensorId) {
            this.sensorId = sensorId
            since = now
        }
        return since
    }

    /** The wait is over (a reading arrived, or the sensor is gone); the next one starts afresh. */
    fun reset() {
        sensorId = null
    }
}

private val firstReadingWait = FirstReadingWait()

/**
 * When a sensor without readings counts as stuck: [STUCK_GRACE_MS] after its warm-up ended or
 * after this app started waiting for it, whichever is later. Warm-up end alone is days in the past
 * for an established sensor — the sensor is published at connect, before its first reading, so
 * every cold start (and every take-over of a sensor already running elsewhere) raised the banner
 * until that reading landed.
 */
internal fun stuckSetupAt(warmupEnd: Long, waitingSince: Long): Long =
    maxOf(warmupEnd, waitingSince) + STUCK_GRACE_MS

/**
 * Sensor status strip below the header. Shows, in priority order: a reported fault (error
 * banner), else a warm-up countdown, else — once warm-up has elapsed and the app has waited out
 * the grace ([stuckSetupAt]) with still no reading — a "setup didn't complete" banner. That last
 * case is the time-based safety net for a Libre 3 handshake that silently never streams (upstream
 * issue #405); the engine-driven connection error covers the case where the failure produced a
 * BLE disconnect.
 *
 * @param reading the latest reading; the sensor counts as streaming once it has one of its own.
 */
@Composable
private fun SensorStatusBanner(sensor: de.mogluco.data.SensorInfo?, reading: GlucoseReading?) {
    if (sensor == null) {
        firstReadingWait.reset()
        return
    }
    // Only a reading taken by THIS sensor counts: right after a replacement the latest reading is
    // still the previous sensor's last value, which hid the new sensor's warm-up countdown and
    // its stuck-setup banner. By the sensor the reading names — its time alone does not tell: a
    // sensor that was not deactivated delivers on after its successor was activated, and the
    // card can pass to the new one with that value still on it. (No name: by its time, as far
    // as that goes.)
    val hasReading = reading != null && reading.epochMillis >= sensor.activationMillis &&
        (reading.sensorId == null || reading.sensorId == sensor.id)
    val waitingSince = remember(sensor.id, hasReading) {
        if (hasReading) {
            firstReadingWait.reset()
            0L
        } else {
            firstReadingWait.since(sensor.id, System.currentTimeMillis())
        }
    }
    sensor.error?.let {
        ErrorBanner(it)
        return
    }
    val warmupEnd = sensor.warmupEndMillis()
    val stuckAt = stuckSetupAt(warmupEnd, waitingSince)
    // Self-tick until data flows or we've decided it's stuck, then stop. Lifecycle-gated so a
    // backgrounded (or pager-retained, off-viewport) screen doesn't keep the countdown ticking;
    // returning to STARTED re-runs the block and re-evaluates immediately.
    val lifecycleOwner = LocalLifecycleOwner.current
    val now by produceState(initialValue = System.currentTimeMillis(), stuckAt, hasReading, lifecycleOwner) {
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
        // This process watched the warm-up end (or began waiting right at its end) and still
        // nothing came: the setup itself is stuck. A sensor that was already running when the
        // app started waiting is a different matter — the sensor's card is there from process
        // start, so "no reading yet" then mostly means Bluetooth off, out of range, or the watch
        // holding the sensor.
        now >= stuckAt && waitingSince <= warmupEnd + STUCK_GRACE_MS -> StuckSetupBanner()
        now >= stuckAt -> NoReadingsYetBanner()
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

/**
 * Shown for a sensor that was already past its warm-up when the app started waiting for it and
 * has delivered nothing for the grace period. Unlike [StuckSetupBanner] this makes no claim about
 * a connection: the usual reasons are Bluetooth off, the sensor out of range, or the watch owning
 * the sensor while it is out of reach.
 */
@Composable
private fun NoReadingsYetBanner() {
    val context = LocalContext.current
    NoteSurface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        icon = Icons.Filled.Bluetooth,
        title = "Waiting for the sensor",
        body = "No reading has arrived since Mogluco started. Keep Bluetooth on and the sensor " +
            "within reach; if your watch has the sensor, readings come through the watch. Still " +
            "nothing after a while? Turn Bluetooth off and on, and make sure no other app or " +
            "phone is connected to this sensor.",
        action = {
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }) { Text("Bluetooth settings") }
        },
    )
}

/** Title of [NewSensorFaultBanner]: the error banner's title, named as the new sensor's. */
internal fun newSensorFaultTitle(sensorId: String, error: de.mogluco.data.SensorError): String =
    "New sensor $sensorId: ${error.title} (code ${error.code})"

/**
 * A newly activated sensor's fault, shown below the status of the sensor on screen: while the
 * previous sensor still delivers, value, alarms and sensor card stay with it, so the new one has
 * no card for its failed setup to appear on (HomeUiState.newSensorFault). Same look and text as
 * [ErrorBanner], with the sensor named and a line on where the readings come from meanwhile.
 */
@Composable
private fun NewSensorFaultBanner(newSensor: SensorInfo?) {
    val error = newSensor?.error ?: return
    NoteSurface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        icon = Icons.Filled.WarningAmber,
        title = newSensorFaultTitle(newSensor.id, error),
        body = "${error.meaning} Readings keep coming from the previous sensor until the new one starts.",
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
                // One line: at the largest font sizes the serial broke before its last character,
                // and the extra line made the pinned cards taller.
                de.mogluco.ui.util.FontScaleAtMost(SERIAL_MAX_FONT_SCALE) {
                    Text(
                        sensor.id,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
    // All five labels ("6h" … "Yesterday") must stay fully readable on a 360dp phone at large
    // font sizes, without wrapping or abbreviating. Equal fifths can't do that — "Yesterday"
    // overflows its fifth (and is cut by the segment's clip) while "6h" wastes most of its own —
    // so each segment is as wide as its label needs: weighted by the measured label width plus
    // the segment's fixed side padding, with a floor that keeps the short ones a full touch
    // target. The label's font scale is also capped at 1.5×; with the weights that keeps all
    // five whole at any system font size, down to a screen about 320dp wide.
    val systemDensity = LocalDensity.current
    val density = remember(systemDensity) {
        if (systemDensity.fontScale > RANGE_LABEL_MAX_FONT_SCALE) {
            Density(systemDensity.density, RANGE_LABEL_MAX_FONT_SCALE)
        } else {
            systemDensity
        }
    }
    val labelStyle = MaterialTheme.typography.labelMedium
    val textMeasurer = rememberTextMeasurer()
    val weights = remember(labelStyle, density) {
        val padding = with(density) { 24.dp.toPx() } // SegmentedButton's own 12dp on each side
        val minWidth = with(density) { 48.dp.toPx() }
        TimeRange.entries.map { r ->
            val label = textMeasurer.measure(r.label, labelStyle, maxLines = 1, density = density)
            (label.size.width + padding).coerceAtLeast(minWidth)
        }
    }
    CompositionLocalProvider(LocalDensity provides density) {
        SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
            TimeRange.entries.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = r == range,
                    onClick = { onRange(r) },
                    shape = SegmentedButtonDefaults.itemShape(i, TimeRange.entries.size),
                    // Takes precedence over the equal weight SegmentedButton applies itself (of
                    // two weights in a chain, the outer one wins).
                    modifier = Modifier.weight(weights[i]),
                    // No check mark, as on every segmented control in the app: the fill marks
                    // the selection, and the check would take the room the labels need.
                    icon = {},
                ) {
                    Text(
                        r.label,
                        // Last resort on a screen too narrow even for the weighted widths: reach
                        // into the segment's padding rather than be cut at the content box.
                        modifier = Modifier.wrapContentWidth(unbounded = true),
                        maxLines = 1,
                        softWrap = false,
                        style = labelStyle,
                    )
                }
            }
        }
    }
}

/** Largest font scale the sensor serial on the sensor card follows. */
private const val SERIAL_MAX_FONT_SCALE = 1.5f

/** Largest font scale the range selector's labels follow; see [RangeSelector]. */
private const val RANGE_LABEL_MAX_FONT_SCALE = 1.5f


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
