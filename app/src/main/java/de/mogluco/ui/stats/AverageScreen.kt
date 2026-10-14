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
package de.mogluco.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.mogluco.data.AverageProfile
import de.mogluco.data.GlucosePoint
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.glucoseState
import de.mogluco.ui.home.HomeViewModel
import de.mogluco.ui.theme.GlucoseColors
import de.mogluco.ui.theme.MoglucoTheme
import de.mogluco.ui.theme.colorFor
import androidx.compose.runtime.collectAsState

/** Ranges offered on the average view (7 / 30 / 90 / 120 days + year-to-date). */
private enum class AvgRange(val label: String) {
    D7("7d"), D30("30d"), D90("90d"), D120("120d"), YTD("YTD");

    /** Window start for this range at [now]. YTD runs from local Jan 1. */
    fun startMillis(now: Long): Long = when (this) {
        D7 -> now - 7 * DAY_MS
        D30 -> now - 30 * DAY_MS
        D90 -> now - 90 * DAY_MS
        D120 -> now - 120 * DAY_MS
        YTD -> startOfYear(now)
    }

    /** Human span for the caption / empty state. */
    fun describe(): String = if (this == YTD) "this year" else "the last ${label.removeSuffix("d")} days"
}

private const val DAY_MS = 24L * 60 * 60 * 1000

private fun startOfYear(now: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = now
    set(java.util.Calendar.MONTH, java.util.Calendar.JANUARY)
    set(java.util.Calendar.DAY_OF_MONTH, 1)
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * "Average day" view: the time-of-day glucose profile over the selected range — the mean trace with
 * a ±1 SD band, faint separators at 06:00 / 12:00 / 18:00, and the average for each of the four
 * 6-hour sections drawn in place (the Libre reader's bar view, as a line). The overall range average
 * is shown above the chart.
 */
@Composable
fun AverageScreen(viewModel: HomeViewModel) {
    val state by viewModel.uiState.collectAsState()
    var rangeIndex by rememberSaveable { mutableIntStateOf(0) }
    val range = AvgRange.entries[rangeIndex]
    val glucose = MoglucoTheme.glucose

    // Recompute only when the range changes or an hour of fresh readings has accrued — not on every
    // incoming reading. A mean over weeks/months can't shift meaningfully between 5-minute readings,
    // yet re-walking up to ~100k points on the composition thread every minute dropped frames. The
    // bucket key still feeds the FULL-resolution history to the average; it only throttles recompute.
    val historyBucket = (state.history.lastOrNull()?.epochMillis ?: 0L) / 3_600_000L
    val profile = remember(historyBucket, rangeIndex) {
        val now = System.currentTimeMillis()
        AverageProfile.from(state.history, range.startMillis(now), now)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Same title style as the other tab that has one (Settings).
        Text("Average day", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            AvgRange.entries.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = i == rangeIndex,
                    onClick = { rangeIndex = i },
                    shape = SegmentedButtonDefaults.itemShape(i, AvgRange.entries.size),
                    icon = {}, // no check mark, as on every segmented control in the app
                ) { Text(r.label) }
            }
        }

        Spacer(Modifier.height(16.dp))
        OverallAverage(profile, state.unit, state.targetLow, state.targetHigh, range.label, glucose)
        Spacer(Modifier.height(8.dp))

        if (profile.totalCount == 0) {
            Text(
                "No readings in ${range.describe()}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            AverageProfileChart(
                profile = profile,
                unit = state.unit,
                targetLow = state.targetLow,
                targetHigh = state.targetHigh,
                glucose = glucose,
                lineColor = MaterialTheme.colorScheme.primary,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                tooltipBg = MaterialTheme.colorScheme.surfaceVariant,
                tooltipText = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .padding(top = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Mean glucose per time of day with a ±1 SD band, over ${range.describe()} " +
                    "(${profile.totalCount} readings). Numbers are the 6-hour section averages.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OverallAverage(
    profile: AverageProfile,
    unit: GlucoseUnit,
    targetLow: Double,
    targetHigh: Double,
    rangeLabel: String,
    glucose: GlucoseColors,
) {
    val mean = profile.overallMean
    Column {
        Text(
            "Overall average",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (mean != null) unit.format(mean) else "—",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = if (mean != null) {
                    glucose.colorFor(glucoseState(mean, targetLow, targetHigh))
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(
                "${unit.label} · $rangeLabel",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun AverageProfileChart(
    profile: AverageProfile,
    unit: GlucoseUnit,
    targetLow: Double,
    targetHigh: Double,
    glucose: GlucoseColors,
    lineColor: Color,
    labelColor: Color,
    tooltipBg: Color,
    tooltipText: Color,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val sectionStyleBase = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val tipStyle = TextStyle(fontSize = 12.sp, color = tooltipText)
    var inspected by remember { mutableStateOf<Int?>(null) }
    // One source of truth for the plot's horizontal insets: the gesture handler (touch→hour) and the
    // DrawScope below must derive the same plot rectangle, or the inspector highlights the wrong hour.
    val leftGutter = 30.dp
    val rightInset = 6.dp

    Canvas(
        modifier.pointerInput(profile) {
            val plotLeft = leftGutter.toPx()
            val plotW = size.width - rightInset.toPx() - plotLeft
            fun hourAt(px: Float): Int? {
                if (plotW <= 0f) return null
                val h = (((px - plotLeft) / plotW) * 24f).toInt()
                return if (h in 0..23 && profile.hourly[h].count > 0) h else null
            }
            // Only after a long press — a quick horizontal swipe still changes tabs.
            detectDragGesturesAfterLongPress(
                onDragStart = { inspected = hourAt(it.x) },
                onDrag = { change, _ -> inspected = hourAt(change.position.x) },
                onDragEnd = { inspected = null },
                onDragCancel = { inspected = null },
            )
        },
    ) {
        val pts = profile.hourly.filter { it.count > 0 }
        if (pts.isEmpty()) return@Canvas

        // Y domain: data (mean ± SD) padded, always containing the target band.
        val dataMin = pts.minOf { it.mean - it.std }
        val dataMax = pts.maxOf { it.mean + it.std }
        val yMin = minOf(dataMin, targetLow) - 10
        val yMax = maxOf(dataMax, targetHigh) + 10

        val leftPad = leftGutter.toPx()
        val bottomPad = 16.dp.toPx()
        val plot = Rect(leftPad, 18.dp.toPx(), size.width - rightInset.toPx(), size.height - bottomPad)
        fun x(hour: Double) = plot.left + (hour / 24.0).toFloat() * plot.width
        fun y(v: Double) = plot.bottom - (((v - yMin) / (yMax - yMin)).toFloat()) * plot.height

        // Target band.
        drawRect(
            color = glucose.inRange.copy(alpha = 0.14f),
            topLeft = Offset(plot.left, y(targetHigh)),
            size = Size(plot.width, y(targetLow) - y(targetHigh)),
        )

        // Faint horizontal grid lines (like the home graph).
        for (i in 1..3) {
            val gy = plot.top + plot.height * i / 4f
            drawLine(labelColor.copy(alpha = 0.15f), Offset(plot.left, gy), Offset(plot.right, gy), 1.dp.toPx())
        }

        // ±1 SD band around the mean (polygon: upper hours ascending, lower hours descending).
        val band = Path()
        pts.forEachIndexed { i, s ->
            val px = x(s.hour + 0.5)
            val py = y(s.mean + s.std)
            if (i == 0) band.moveTo(px, py) else band.lineTo(px, py)
        }
        for (i in pts.indices.reversed()) {
            val s = pts[i]
            band.lineTo(x(s.hour + 0.5), y(s.mean - s.std))
        }
        band.close()
        drawPath(band, color = lineColor.copy(alpha = 0.16f))

        // Section separators at 06:00 / 12:00 / 18:00.
        val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 7f))
        for (h in intArrayOf(6, 12, 18)) {
            drawLine(
                labelColor.copy(alpha = 0.35f),
                Offset(x(h.toDouble()), plot.top),
                Offset(x(h.toDouble()), plot.bottom),
                1.dp.toPx(),
                pathEffect = dash,
            )
        }

        // Mean trace.
        val line = Path()
        pts.forEachIndexed { i, s ->
            val px = x(s.hour + 0.5)
            val py = y(s.mean)
            if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
        }
        drawPath(
            line,
            color = lineColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Section averages, centered in each 6-hour section near the top, colored by state.
        profile.sectionMeans.forEachIndexed { s, m ->
            if (m == null) return@forEachIndexed
            val midHour = AverageProfile.SECTION_BOUNDS[s] + 3.0
            val color = glucose.colorFor(glucoseState(m, targetLow, targetHigh))
            val layout = textMeasurer.measure(unit.format(m), sectionStyleBase.copy(color = color))
            drawText(
                layout,
                topLeft = Offset(x(midHour) - layout.size.width / 2f, plot.top + 2.dp.toPx()),
            )
        }

        // Y scale labels (min/max) in the gutter.
        listOf(yMax to plot.top, yMin to plot.bottom).forEach { (v, py) ->
            val layout = textMeasurer.measure(unit.format(v), axisStyle)
            drawText(layout, topLeft = Offset(leftPad - layout.size.width - 4.dp.toPx(), py - layout.size.height / 2f))
        }

        // Hour axis labels 0 / 6 / 12 / 18 / 24.
        for (h in intArrayOf(0, 6, 12, 18, 24)) {
            val layout = textMeasurer.measure("$h", axisStyle)
            val cx = (x(h.toDouble()) - layout.size.width / 2f)
                .coerceIn(0f, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(cx, plot.bottom + 2.dp.toPx()))
        }

        // Hold-to-inspect tooltip: value + trend (vs the neighbouring hour), count, SD.
        inspected?.let { h ->
            val s = profile.hourly[h]
            if (s.count > 0) {
                val cx = x(h + 0.5)
                drawLine(labelColor.copy(alpha = 0.6f), Offset(cx, plot.top), Offset(cx, plot.bottom), 1.dp.toPx())
                drawCircle(lineColor, 3.5.dp.toPx(), Offset(cx, y(s.mean)))
                val prev = (h - 1 downTo 0).firstNotNullOfOrNull { i -> profile.hourly[i].takeIf { it.count > 0 } }
                val glyph = prev?.let {
                    val d = s.mean - it.mean
                    when { d >= 10 -> "↑"; d >= 3 -> "↗"; d > -3 -> "→"; d > -10 -> "↘"; else -> "↓" }
                } ?: ""
                val lines = listOf(
                    "%02d:00–%02d:00".format(h, (h + 1) % 24),
                    "${unit.format(s.mean)} ${unit.label} $glyph".trim(),
                    "n=${s.count} · SD ±${unit.format(s.std)}",
                )
                val layouts = lines.map { textMeasurer.measure(it, tipStyle) }
                val padPx = 8.dp.toPx()
                val boxW = layouts.maxOf { it.size.width }.toFloat() + padPx * 2
                val boxH = layouts.sumOf { it.size.height }.toFloat() + padPx * 2
                val bx = (cx + 8.dp.toPx()).coerceIn(0f, (size.width - boxW).coerceAtLeast(0f))
                val by = plot.top + 2.dp.toPx()
                drawRoundRect(
                    color = tooltipBg,
                    topLeft = Offset(bx, by),
                    size = Size(boxW, boxH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                )
                var ty = by + padPx
                layouts.forEach { l ->
                    drawText(l, topLeft = Offset(bx + padPx, ty))
                    ty += l.size.height
                }
            }
        }
    }
}
