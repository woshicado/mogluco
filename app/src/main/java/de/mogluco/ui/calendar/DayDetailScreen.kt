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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.mogluco.data.EventCategory
import de.mogluco.ui.util.addDays
import de.mogluco.ui.util.copyToClipboard
import de.mogluco.ui.util.localDaysBetween
import de.mogluco.ui.util.nextLocalMidnight
import de.mogluco.ui.util.startOfDay
import de.mogluco.data.GlucoseReading
import de.mogluco.ui.home.AddEventFab
import de.mogluco.ui.home.BottomListMode
import de.mogluco.ui.home.EnabledCategoriesSaver
import de.mogluco.ui.home.EventLegend
import de.mogluco.ui.home.EventSheetTarget
import de.mogluco.ui.home.EventSheets
import de.mogluco.ui.home.GlucoseChart
import de.mogluco.ui.home.HomeViewModel
import de.mogluco.ui.home.ReadingDetailSaver
import de.mogluco.ui.home.ReadingDetailSheet
import de.mogluco.ui.home.ReadingEventList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How far back the day-swipe pager can travel (≈10 years), comfortably covering all stored data. */
private const val HISTORY_DAYS = 3660

/**
 * Full-screen detail for a single day: a fixed 1-day graph plus the same interaction set as
 * Home (event legend toggles, draggable inspector, readings/events lists, and the + to log
 * events). Reached from the Calendar via the "open day" affordance next to the date.
 *
 * The day content lives in a [HorizontalPager] so the user can swipe left/right to the
 * previous/next day — interruptible, with a preview of the neighbouring day, just like the
 * top-level tab swipe. The rightmost page is today (or the opened day, if it is in the future),
 * so you can't swipe into empty future days. The legend/list-mode/maximize state is shared across
 * days (hoisted above the pager) so swiping keeps your current view.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DayDetailScreen(viewModel: HomeViewModel, dayStartMillis: Long, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // View state shared across days, so swiping keeps the legend filters / list mode / maximize.
    // All saveable, with Home's savers: a plain remember reset the view on rotation and closed an
    // open event sheet mid-entry, discarding what was typed.
    var enabledCategories by rememberSaveable(stateSaver = EnabledCategoriesSaver) {
        mutableStateOf(EventCategory.entries.toSet())
    }
    var listMode by rememberSaveable { mutableStateOf(BottomListMode.EVENTS) }
    var maximized by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable(stateSaver = EventSheetTarget.Saver) {
        mutableStateOf<EventSheetTarget?>(null)
    }
    var readingDetail by rememberSaveable(stateSaver = ReadingDetailSaver) {
        mutableStateOf<GlucoseReading?>(null)
    }

    val displayTypes = remember(state.enabledLogKinds) { viewModel.displayTypes(state.enabledLogKinds) }
    val legendCategories = remember(state.enabledCategories) {
        de.mogluco.ui.home.defaultLegendCategories.filter { it in state.enabledCategories }
    }
    if (maximized) BackHandler { maximized = false }

    // Map pager pages → days. The last page is the most recent day shown (today, or the opened
    // day if it lies in the future); earlier pages step one CALENDAR day back each — fixed-24h
    // millis arithmetic pointed at hour-shifted "midnights" (wrong dates) past a DST transition.
    val anchorDay = remember(dayStartMillis) { startOfDay(dayStartMillis) }
    // Saved with the pager's page index, which only means something relative to it: recomputed
    // from "today" after the activity was recreated on a later date, the restored index pointed
    // one day on — another day's data, and "+" logging to that day.
    val maxDay = rememberSaveable(anchorDay) { maxOf(startOfDay(System.currentTimeMillis()), anchorDay) }
    val pageCount = HISTORY_DAYS + 1
    fun dayForPage(page: Int): Long = addDays(maxDay, -(pageCount - 1 - page))
    val initialPage = remember(anchorDay, maxDay) {
        val back = localDaysBetween(anchorDay, maxDay).coerceIn(0, pageCount - 1)
        pageCount - 1 - back
    }
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })
    val currentDay = dayForPage(pagerState.currentPage)

    val context = androidx.compose.ui.platform.LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(currentDay))) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            )
        },
        floatingActionButton = {},
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val day = dayForPage(page)
                DayContent(
                    state = state,
                    dayStartMillis = day,
                    maximized = maximized,
                    enabledCategories = enabledCategories,
                    legendCategories = legendCategories,
                    listMode = listMode,
                    onListMode = { listMode = it },
                    onToggleCategory = { cat ->
                        enabledCategories =
                            if (cat in enabledCategories) enabledCategories - cat else enabledCategories + cat
                    },
                    onToggleMaximize = { maximized = !maximized },
                    onEventClick = { editing = EventSheetTarget.Edit(it) },
                    onReadingClick = { readingDetail = it },
                    onCopySensor = { sensor, expired ->
                        val text = "Sensor ${sensor.id}\nActivated ${shortDate(sensor.activationMillis)}\n" +
                            "${if (expired) "Expired" else "Expires"} ${shortDate(sensor.expiryMillis())}"
                        copyToClipboard(context, "Sensor", text)
                    },
                )
            }

            AddEventFab(types = displayTypes, onPick = { editing = EventSheetTarget.New(it) })
        }
    }

    EventSheets(
        editing = editing,
        types = displayTypes,
        onDismiss = { editing = null },
        onLog = { type, value, at, note -> viewModel.logEvent(type, value, at, note) },
        onUpdate = { original, edited -> viewModel.updateEvent(original, edited) },
        onDelete = { viewModel.deleteEvent(it) },
        // "+" on a past day's page logs to that day, not to today.
        newEventDay = currentDay,
    )

    readingDetail?.let { ReadingDetailSheet(it, state.unit, System.currentTimeMillis()) { readingDetail = null } }
}

/** One day's panel: sensor card, 1-day graph (hidden when maximized), and the readings/events list. */
@Composable
private fun DayContent(
    state: de.mogluco.ui.home.HomeUiState,
    dayStartMillis: Long,
    maximized: Boolean,
    enabledCategories: Set<EventCategory>,
    legendCategories: List<EventCategory>,
    listMode: BottomListMode,
    onListMode: (BottomListMode) -> Unit,
    onToggleCategory: (EventCategory) -> Unit,
    onToggleMaximize: () -> Unit,
    onEventClick: (de.mogluco.data.LoggedEvent) -> Unit,
    onReadingClick: (GlucoseReading) -> Unit,
    onCopySensor: (de.mogluco.data.SensorInfo, Boolean) -> Unit,
) {
    // Half-open [midnight, next midnight): DST-safe (a fall-back day keeps its 25th hour) and −1
    // keeps a boundary reading out of both neighbouring days (chart/list windows are end-inclusive).
    val dayEnd = nextLocalMidnight(dayStartMillis) - 1
    val sensor = remember(dayStartMillis, state.sensor, state.sensorHistory) {
        sensorOnDay(state.sensor, state.sensorHistory, dayStartMillis)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (!maximized) {
            // Always show a sensor panel (like the calendar). When no sensor was active on
            // this day it stays visible, just saying so — rather than vanishing.
            val expired = sensor?.let { it.expiryMillis() <= System.currentTimeMillis() } ?: false
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).then(
                    if (sensor != null) Modifier.clickable { onCopySensor(sensor, expired) } else Modifier,
                ),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (sensor != null) {
                        Column(Modifier.weight(1f)) {
                            Text("Sensor ${sensor.id}", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            // Same clamped calendar-day badge as the calendar header — omitted
                            // when the day lies outside the sensor's life (was "day 44 / 14").
                            val dayBadge = sensorDayNumber(sensor, dayStartMillis)
                                ?.let { " · day $it / ${sensor.lifetimeDays}" } ?: ""
                            Text(
                                "Activated ${shortDate(sensor.activationMillis)} · ${if (expired) "expired" else "expires"} ${shortDate(sensor.expiryMillis())}$dayBadge",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Copy sensor info",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(Modifier.weight(1f)) {
                            Text("No sensor", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Text(
                                "No sensor was active on this day.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    EventLegend(
                        enabled = enabledCategories,
                        onToggle = onToggleCategory,
                        categories = legendCategories,
                    )
                    GlucoseChart(
                        points = state.history,
                        events = state.events,
                        unit = state.unit,
                        targetLow = state.targetLow,
                        targetHigh = state.targetHigh,
                        windowStart = dayStartMillis,
                        windowEnd = dayEnd,
                        // Reset the inspector when the day changes (each day has the same duration).
                        resetKey = dayStartMillis,
                        readingDisplay = state.readingDisplay,
                        enabledEventCategories = enabledCategories,
                        showTargetBand = state.showTargetBand,
                        gapThresholdMillis = state.gapThresholdMillis,
                        onEventClick = onEventClick,
                        modifier = Modifier.fillMaxWidth().height(240.dp).padding(horizontal = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Card(
            Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            ReadingEventList(
                mode = listMode,
                onMode = onListMode,
                history = state.history,
                events = state.events,
                unit = state.unit,
                windowStart = dayStartMillis,
                windowEnd = dayEnd,
                onReadingClick = onReadingClick,
                onEventClick = onEventClick,
                readingDisplay = state.readingDisplay,
                modifier = Modifier.fillMaxSize().padding(12.dp),
                // As on Home: let the last rows scroll clear of the speed-dial FAB.
                bottomPadding = 88,
                maximized = maximized,
                onToggleMaximize = onToggleMaximize,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun shortDate(millis: Long): String =
    SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(millis))
