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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.mogluco.data.EventCategory
import de.mogluco.data.GlucosePoint
import de.mogluco.data.GlucoseReading
import de.mogluco.data.ReadingDisplay
import de.mogluco.data.windowed
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.LoggedEvent
import de.mogluco.data.TrendArrow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The event categories shown in the legend. OTHER (notes) is included so its chart markers can be
 *  toggled off like every other category — their markers are always drawn, so they need a chip. */
val defaultLegendCategories: List<EventCategory> = listOf(
    EventCategory.CARBS, EventCategory.INSULIN, EventCategory.ACTIVITY, EventCategory.BLOOD,
)

/** Per-category event legend; tapping a chip toggles that category's events on the chart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventLegend(
    enabled: Set<EventCategory>,
    onToggle: (EventCategory) -> Unit,
    modifier: Modifier = Modifier,
    categories: List<EventCategory> = defaultLegendCategories,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEach { cat ->
            val on = cat in enabled
            FilterChip(
                selected = on,
                onClick = { onToggle(cat) },
                label = { Text(cat.displayName()) },
                leadingIcon = {
                    Box(
                        Modifier.size(10.dp).clip(CircleShape).background(
                            if (on) eventColor(cat) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        ),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

enum class BottomListMode(val label: String) { READINGS("Readings"), EVENTS("Events") }

/**
 * The Readings / Events list with a mode toggle, used on Home, in the Calendar day detail,
 * and the full day-detail page. Rows are interactive: readings open a detail sheet, events
 * open the edit sheet. The list itself scrolls; the caller decides how much height it gets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingEventList(
    mode: BottomListMode,
    onMode: (BottomListMode) -> Unit,
    history: List<GlucosePoint>,
    events: List<LoggedEvent>,
    unit: GlucoseUnit,
    windowStart: Long,
    windowEnd: Long,
    onReadingClick: (GlucoseReading) -> Unit,
    onEventClick: (LoggedEvent) -> Unit,
    readingDisplay: ReadingDisplay = ReadingDisplay.BOTH,
    modifier: Modifier = Modifier,
    bottomPadding: Int = 0,
    maximized: Boolean = false,
    onToggleMaximize: (() -> Unit)? = null,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                BottomListMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = m == mode,
                        onClick = { onMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, BottomListMode.entries.size),
                    ) { Text(m.label) }
                }
            }
            // Maximize/restore: hides the graph to give the list the whole screen (a back-
            // dismissable sub-view). Offered only where the caller passes a handler.
            if (onToggleMaximize != null) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onToggleMaximize) {
                    Icon(
                        if (maximized) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull,
                        contentDescription = if (maximized) "Restore graph" else "Maximize list",
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val pad = PaddingValues(bottom = bottomPadding.dp)
        when (mode) {
            BottomListMode.READINGS -> {
                val inWindow = remember(history, windowStart, windowEnd) {
                    history.windowed(windowStart, windowEnd).reversed()
                }
                if (inWindow.isEmpty()) {
                    EmptyHint("No readings in this range")
                } else {
                    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        // Timestamps are unique (history is de-duped), so they make a stable key.
                        // Keying by index shifted the viewport every time a new reading prepended.
                        itemsIndexed(inWindow, key = { _, pt -> pt.epochMillis }) { idx, pt ->
                            ReadingRow(pt, inWindow.getOrNull(idx + 1), unit, onReadingClick, readingDisplay)
                        }
                    }
                }
            }
            BottomListMode.EVENTS -> {
                val inWindow = remember(events, windowStart, windowEnd) {
                    events.filter { it.epochMillis in windowStart..windowEnd }
                        .sortedByDescending { it.epochMillis }
                }
                if (inWindow.isEmpty()) {
                    EmptyHint("No events in this range")
                } else {
                    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Key by list index: events have no stable id and may fully duplicate
                        // (same time+type+value, e.g. from a backup imported twice), so any
                        // content-derived key can collide and crash LazyColumn. The index can't.
                        items(inWindow.size, key = { idx -> idx }) { idx ->
                            EventRow(inWindow[idx], onEventClick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(12.dp),
    )
}

@Composable
fun ReadingRow(
    pt: GlucosePoint,
    prev: GlucosePoint?,
    unit: GlucoseUnit,
    onClick: (GlucoseReading) -> Unit,
    readingDisplay: ReadingDisplay = ReadingDisplay.BOTH,
) {
    val rawOnly = readingDisplay == ReadingDisplay.RAW
    val rate = prev?.let {
        (pt.calibratedMgdl - it.calibratedMgdl) / ((pt.epochMillis - it.epochMillis) / 60_000.0)
    } ?: 0.0
    val reading = GlucoseReading(
        calibratedMgdl = pt.calibratedMgdl,
        rawMgdl = pt.rawMgdl,
        epochMillis = pt.epochMillis,
        trend = TrendArrow.fromRate(rate),
        ratePerMin = rate,
    )
    Row(
        // heightIn before clickable so the whole 48dp minimum touch target is tappable, not
        // just the ~36dp of text.
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onClick(reading) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            timeShort(pt.epochMillis),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Text(
            "${unit.format(if (rawOnly) pt.rawMgdl else pt.calibratedMgdl)} ${unit.label}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(8.dp))
        TrendArrow(reading = reading, tint = MaterialTheme.colorScheme.onSurface, size = 20.dp)
        Spacer(Modifier.weight(1f))
        // No second value when only raw is shown (the primary value already IS raw).
        if (!rawOnly) {
            Text(
                "raw ${unit.format(pt.rawMgdl)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
fun EventRow(e: LoggedEvent, onClick: (LoggedEvent) -> Unit) {
    Surface(
        onClick = { onClick(e) },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(eventColor(e.category).copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(eventIcon(e.category, e.label), null, tint = eventColor(e.category), modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${e.label.ifEmpty { "Event" }} · ${eventValueLabel(e)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (e.note.isNotEmpty()) {
                    Text(e.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                timeShort(e.epochMillis),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Reused rather than re-allocated per row/recomposition while scrolling the list.
private val shortTimeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

internal fun timeShort(epochMillis: Long): String = shortTimeFmt.format(Date(epochMillis))
