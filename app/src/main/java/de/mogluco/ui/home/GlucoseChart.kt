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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.mogluco.data.EventCategory
import de.mogluco.data.glucoseState
import de.mogluco.data.unit
import de.mogluco.data.GlucosePoint
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.buildGapLine
import de.mogluco.data.windowed
import de.mogluco.data.LoggedEvent
import de.mogluco.data.ReadingDisplay
import de.mogluco.data.TrendArrow
import kotlinx.coroutines.withTimeoutOrNull
import de.mogluco.ui.theme.MoglucoTheme
import de.mogluco.ui.theme.colorFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * How long the finger must be held still on the chart before a drag scrubs the inspector tooltip
 * (rather than swiping the parent pager). Shorter than the system long-press (~400 ms) so the
 * tooltip appears promptly; a quick flick still moves past touch-slop first and changes tab/day.
 */
private const val TOOLTIP_HOLD_MS = 200L

/** Color for an event category's marker line/label. */
fun eventColor(category: EventCategory): Color = when (category) {
    EventCategory.CARBS -> Color(0xFFF59E0B)      // amber
    EventCategory.INSULIN -> Color(0xFF3B82F6)    // blue
    EventCategory.ACTIVITY -> Color(0xFF22C55E)   // green
    EventCategory.BLOOD -> Color(0xFFE11D48)      // crimson (blood reference)
    EventCategory.OTHER -> Color(0xFF8B5CF6)      // violet
}

/**
 * Fully custom, interactive glucose chart. Drawn on a [Canvas] (rather than Vico) so it can
 * own the behaviour the design calls for: a draggable inspector line with a value tooltip,
 * color-coded event marker lines whose value pills dodge each other, dotted labelled min/max
 * lines, and a shaded target band over a grid. Reused by Home, the day detail and Calendar.
 *
 * Lines are clipped to the plot rect so they never overshoot the axes. The inspector uses
 * horizontal drags only, so vertical scrolling passes through to a scrolling parent.
 */
@Composable
fun GlucoseChart(
    points: List<GlucosePoint>,
    events: List<LoggedEvent>,
    unit: GlucoseUnit,
    targetLow: Double,
    targetHigh: Double,
    windowStart: Long,
    windowEnd: Long,
    readingDisplay: ReadingDisplay,
    enabledEventCategories: Set<EventCategory>,
    showTargetBand: Boolean,
    gapThresholdMillis: Long,
    onEventClick: (LoggedEvent) -> Unit,
    modifier: Modifier = Modifier,
    // Distinguishes ranges with the same duration (Home's "1d" vs "Today" are both 24h):
    // gesture handlers and the inspector reset when this changes, not just on duration.
    resetKey: Any? = null,
) {
    val density = LocalDensity.current
    // A frame measures 13+ labels (axes, pills, tooltip rows); the default cache of 8 thrashes
    // and re-measures text every redraw of an inspector drag.
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val glucose = MoglucoTheme.glucose

    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val calibratedColor = MaterialTheme.colorScheme.primary
    // Raw is grey only when BOTH lines draw (to distinguish it); when it's the sole line it takes
    // the primary style like the smoothed trace.
    val rawColor = if (readingDisplay == ReadingDisplay.RAW) {
        calibratedColor
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }
    val rawStrokeWidth = if (readingDisplay == ReadingDisplay.RAW) 3f else 2f
    val bandColor = glucose.inRange.copy(alpha = 0.13f)
    val inspectColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val tooltipBg = MaterialTheme.colorScheme.surfaceVariant
    val tooltipText = MaterialTheme.colorScheme.onSurfaceVariant

    val duration = (windowEnd - windowStart).coerceAtLeast(1L)
    val showRaw = readingDisplay != ReadingDisplay.SMOOTHED
    val showCal = readingDisplay != ReadingDisplay.RAW // showCal == show the smoothed trace

    // Binary-search the visible window (history is time-sorted) instead of scanning the whole
    // history every recomposition — the chart only ever needs the points it draws.
    val visible = remember(points, windowStart, windowEnd) {
        points.windowed(windowStart - 6 * 60_000, windowEnd + 6 * 60_000)
    }
    val visibleEvents = remember(events, windowStart, windowEnd, enabledEventCategories) {
        events.filter { it.epochMillis in windowStart..windowEnd && it.category in enabledEventCategories }
            .sortedBy { it.epochMillis }
    }

    fun stateColorFor(mgdl: Double): Color =
        glucose.colorFor(glucoseState(mgdl, targetLow, targetHigh))

    BoxWithConstraints(modifier) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val leftAxis = with(density) { 40.dp.toPx() }
        val topPad = with(density) { 32.dp.toPx() }   // room for dodging event pills
        val bottomAxis = with(density) { 22.dp.toPx() }
        val rightPad = with(density) { 10.dp.toPx() }
        val plotL = leftAxis
        val plotT = topPad
        val plotR = wPx - rightPad
        val plotB = hPx - bottomAxis
        val plotW = (plotR - plotL).coerceAtLeast(1f)
        val plotH = (plotB - plotT).coerceAtLeast(1f)

        // Y-range depends only on the data/unit/target (not pixel size), so remember it rather
        // than rescanning every point on each recomposition.
        val (yMin, yMax, ySpan) = remember(visible, showCal, showRaw, unit, targetLow, targetHigh) {
            val displayValues = buildList {
                visible.forEach {
                    if (showCal) add(unit.fromMgdl(it.calibratedMgdl))
                    if (showRaw) add(unit.fromMgdl(it.rawMgdl))
                }
                add(unit.fromMgdl(targetLow)); add(unit.fromMgdl(targetHigh))
            }
            val rawMin = displayValues.minOrNull() ?: unit.fromMgdl(40.0)
            val rawMax = displayValues.maxOrNull() ?: unit.fromMgdl(250.0)
            val pad = ((rawMax - rawMin) * 0.12).coerceAtLeast(unit.fromMgdl(10.0))
            val lo = rawMin - pad
            val hi = rawMax + pad
            Triple(lo, hi, (hi - lo).coerceAtLeast(1e-3))
        }

        fun xOf(t: Long): Float = plotL + ((t - windowStart).toFloat() / duration) * plotW
        fun yOf(v: Double): Float = plotB - (((v - yMin) / ySpan).toFloat()) * plotH

        val calVisible = remember(visible, windowStart, windowEnd) {
            visible.filter { it.epochMillis in windowStart..windowEnd }
        }
        val calMin = remember(calVisible) { calVisible.minByOrNull { it.calibratedMgdl } }
        val calMax = remember(calVisible) { calVisible.maxByOrNull { it.calibratedMgdl } }

        // Build the trace Paths once per data/geometry change, not on every Canvas draw — the
        // inspector drag redraws the Canvas continuously and rebuilding the Path each frame
        // (O(points)) was the main per-frame cost.
        val rawPath = remember(visible, plotL, plotW, plotB, plotH, yMin, ySpan, windowStart, duration, unit, showRaw, gapThresholdMillis) {
            if (showRaw && visible.size >= 2) buildLine(visible, gapThresholdMillis, ::xOf) { yOf(unit.fromMgdl(it.rawMgdl)) } else null
        }
        val calPath = remember(visible, plotL, plotW, plotB, plotH, yMin, ySpan, windowStart, duration, unit, showCal, gapThresholdMillis) {
            if (showCal && visible.size >= 2) buildLine(visible, gapThresholdMillis, ::xOf) { yOf(unit.fromMgdl(it.calibratedMgdl)) } else null
        }

        // The 30s clock slides windowStart/windowEnd on every tick, so the gesture handlers must
        // NOT be keyed on them: a restart cancels an in-flight scrub and a tap-placed inspector
        // vanished within 30s. They're keyed on stable identity instead — the window *duration*
        // (changes only with the range picker) and the plot geometry — and read the moving
        // window/events through rememberUpdatedState.
        val windowStartLive by rememberUpdatedState(windowStart)
        val windowEndLive by rememberUpdatedState(windowEnd)
        val visibleEventsLive by rememberUpdatedState(visibleEvents)
        val onEventClickLive by rememberUpdatedState(onEventClick)
        // Keyed on duration (i.e. the picked range), not the sliding window, so the inspector
        // survives ticks; it resets on a range change, where its old pixel x would point at a
        // different moment in time.
        var inspectX by remember(duration, resetKey) { mutableStateOf<Float?>(null) }
        val eventHitPx = with(density) { 14.dp.toPx() }
        // Only events essentially at the inspected point appear in the tooltip.
        val eventNearPx = with(density) { 9.dp.toPx() }
        // A tap this close to the inspector line dismisses it instead of moving it.
        val inspectDismissPx = with(density) { 24.dp.toPx() }

        // The Canvas has no child semantics — summarize the window and latest reading for
        // TalkBack instead of leaving the chart silent.
        val chartDescription = remember(calVisible, unit, windowStart, windowEnd) {
            val latest = calVisible.lastOrNull()
            if (latest == null) {
                "Glucose chart, no readings in this range"
            } else {
                val prev = calVisible.getOrNull(calVisible.size - 4) ?: calVisible.getOrNull(calVisible.size - 2)
                val trend = prev?.let {
                    val rate = (latest.calibratedMgdl - it.calibratedMgdl) /
                        (((latest.epochMillis - it.epochMillis).coerceAtLeast(1)) / 60_000.0)
                    TrendArrow.fromRate(rate).name.lowercase().replace('_', ' ')
                }
                "Glucose chart from ${axisTimeFmt.format(Date(windowStart))} to " +
                    "${axisTimeFmt.format(Date(windowEnd))}. Latest ${unit.format(latest.calibratedMgdl)} " +
                    "${unit.label} at ${axisTimeFmt.format(Date(latest.epochMillis))}" +
                    (trend?.let { ", trend $it" } ?: "")
            }
        }

        Canvas(
            modifier = Modifier
                .matchParentSize()
                .semantics { contentDescription = chartDescription }
                .pointerInput(duration, resetKey, plotL, plotR) {
                    detectTapGestures { off ->
                        // Live values: this handler outlives many 30s window ticks by design.
                        val ws = windowStartLive
                        val dur = (windowEndLive - ws).coerceAtLeast(1L)
                        fun xAt(t: Long): Float = plotL + ((t - ws).toFloat() / dur) * plotW
                        val hit = visibleEventsLive.minByOrNull { abs(xAt(it.epochMillis) - off.x) }
                            ?.takeIf { abs(xAt(it.epochMillis) - off.x) <= eventHitPx }
                        val ix = inspectX
                        when {
                            hit != null -> onEventClickLive(hit)
                            // Second tap on/near the inspector line dismisses it.
                            ix != null && abs(off.x - ix) <= inspectDismissPx -> inspectX = null
                            else -> inspectX = off.x.coerceIn(plotL, plotR)
                        }
                    }
                }
                .pointerInput(duration, resetKey, plotL, plotR) {
                    // Scrub the inspector after a short hold; a quick flick (moves past touch-slop
                    // before the hold elapses) is left to the parent pager (tab/day swap) so swiping
                    // still works. The hold is deliberately shorter than the system long-press
                    // ([TOOLTIP_HOLD_MS]) so the tooltip appears promptly when you do hold.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        // Null = the hold elapsed with the finger held still → take over scrubbing.
                        // Non-null = the finger lifted or moved past slop first → leave it to swipe.
                        val held = withTimeoutOrNull(TOOLTIP_HOLD_MS) {
                            var bail = false
                            while (!bail) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                bail = change == null || !change.pressed ||
                                    (change.position - start).getDistance() > viewConfiguration.touchSlop
                            }
                        }
                        if (held == null) {
                            inspectX = start.x.coerceIn(plotL, plotR)
                            drag(down.id) { change ->
                                inspectX = change.position.x.coerceIn(plotL, plotR)
                                change.consume()
                            }
                            inspectX = null
                        }
                    }
                },
        ) {
            val labelStyle = TextStyle(color = axisTextColor, fontSize = 9.sp)

            if (showTargetBand) {
                val bandTop = yOf(unit.fromMgdl(targetHigh))
                val bandBottom = yOf(unit.fromMgdl(targetLow))
                drawRect(bandColor, Offset(plotL, bandTop), Size(plotW, (bandBottom - bandTop).coerceAtLeast(0f)))
            }

            // Horizontal grid + y labels
            val yStep = niceStep(ySpan / 4.0)
            var gv = ceil(yMin / yStep) * yStep
            while (gv <= yMax) {
                val y = yOf(gv)
                drawLine(gridColor, Offset(plotL, y), Offset(plotR, y), strokeWidth = 1f)
                drawAxisLabel(textMeasurer, formatAxis(gv, unit), Offset(plotL - 4f, y), labelStyle, alignEnd = true)
                gv += yStep
            }

            // Vertical grid + time labels
            val tStep = niceTimeStep(duration)
            val fmt = timeFormatter(duration)
            var tv = (floor(windowStart.toDouble() / tStep) * tStep).toLong()
            if (tv < windowStart) tv += tStep
            while (tv <= windowEnd) {
                val x = xOf(tv)
                drawLine(gridColor, Offset(x, plotT), Offset(x, plotB), strokeWidth = 1f)
                drawAxisLabel(textMeasurer, fmt.format(Date(tv)), Offset(x, plotB + 3f), labelStyle, center = true)
                tv += tStep
            }

            // Dotted min/max calibrated lines — labels right-aligned so they rarely collide
            // with the trace (which is usually busiest on the left/recent side).
            if (showCal) {
                val dotted = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                val maxLabelStyle = TextStyle(color = calibratedColor, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                calMax?.let {
                    val y = yOf(unit.fromMgdl(it.calibratedMgdl))
                    drawLine(calibratedColor.copy(alpha = 0.5f), Offset(plotL, y), Offset(plotR, y), strokeWidth = 1.5f, pathEffect = dotted)
                    drawAxisLabel(textMeasurer, unit.format(it.calibratedMgdl), Offset(plotR - 4f, y - 12f), maxLabelStyle, alignEnd = true)
                }
                calMin?.let {
                    val y = yOf(unit.fromMgdl(it.calibratedMgdl))
                    drawLine(calibratedColor.copy(alpha = 0.5f), Offset(plotL, y), Offset(plotR, y), strokeWidth = 1.5f, pathEffect = dotted)
                    // Drop the label fully below the line (mirroring the max label above it); the
                    // axis label is vertically centred on its anchor, so +2f straddled the line.
                    drawAxisLabel(textMeasurer, unit.format(it.calibratedMgdl), Offset(plotR - 4f, y + 12f), maxLabelStyle, alignEnd = true)
                }
            }

            // Event marker lines + dodging value pills
            drawEventMarkers(textMeasurer, visibleEvents, plotL, plotR, plotT, plotB) { xOf(it.epochMillis) }

            // Glucose lines, clipped to the plot rect so they never overshoot the axes.
            clipRect(left = plotL, top = 0f, right = plotR, bottom = hPx) {
                rawPath?.let { drawPath(it, rawColor, style = Stroke(width = rawStrokeWidth)) }
                calPath?.let { drawPath(it, calibratedColor, style = Stroke(width = 3f)) }
            }

            // Inspector
            inspectX?.let { ix ->
                val t = windowStart + ((ix - plotL) / plotW * duration).toLong()
                // One pass finds the nearest point AND its index (indexOf below was a 2nd O(n) scan).
                val nearestEntry = visible.withIndex().minByOrNull { abs(it.value.epochMillis - t) }
                if (nearestEntry != null) {
                    val nearest = nearestEntry.value
                    val idx = nearestEntry.index
                    val nx = xOf(nearest.epochMillis)
                    drawLine(inspectColor, Offset(nx, plotT), Offset(nx, plotB), strokeWidth = 1.5f)
                    if (showCal) drawInspectDot(nx, yOf(unit.fromMgdl(nearest.calibratedMgdl)), calibratedColor, surfaceColor)
                    if (showRaw) drawInspectDot(nx, yOf(unit.fromMgdl(nearest.rawMgdl)), rawColor, surfaceColor)
                    val nearby = visibleEvents.filter { abs(xOf(it.epochMillis) - nx) <= eventNearPx }
                    val bigVal = if (showCal) nearest.calibratedMgdl else nearest.rawMgdl
                    // Local rate of change for the inspected point → trend-arrow angle.
                    val prev = visible.getOrNull(idx - 3) ?: visible.getOrNull(idx - 1)
                    val angle = if (prev != null) {
                        val pv = if (showCal) prev.calibratedMgdl else prev.rawMgdl
                        val rate = (bigVal - pv) / (((nearest.epochMillis - prev.epochMillis).coerceAtLeast(1)) / 60_000.0)
                        TrendArrow.angleDegrees(rate)
                    } else 0f
                    drawTooltip(
                        textMeasurer, nearest, unit, showRaw, showCal, nearby, angle,
                        nx, plotT, plotL, plotR, tooltipBg, tooltipText, stateColorFor(bigVal), rawColor,
                    )
                }
            }
        }
    }
}

private fun buildLine(
    points: List<GlucosePoint>,
    gapMillis: Long,
    xOf: (Long) -> Float,
    yOf: (GlucosePoint) -> Float,
): Path {
    val path = Path()
    buildGapLine(points, gapMillis, { xOf(it.epochMillis) }, yOf, { x, y -> path.moveTo(x, y) }, { x, y -> path.lineTo(x, y) })
    return path
}

private fun DrawScope.drawInspectDot(x: Float, y: Float, color: Color, ring: Color) {
    drawCircle(ring, radius = 6f, center = Offset(x, y))
    drawCircle(color, radius = 4f, center = Offset(x, y))
}

/**
 * Draws each event's vertical line plus a small value pill at the top. Pills are packed into
 * rows greedily so neighbours that would overlap are pushed onto a row above instead, keeping
 * every label readable even when events cluster (e.g. a meal and its bolus at the same time).
 */
private fun DrawScope.drawEventMarkers(
    tm: TextMeasurer,
    events: List<LoggedEvent>,
    plotL: Float,
    plotR: Float,
    plotT: Float,
    plotB: Float,
    xOf: (LoggedEvent) -> Float,
) {
    if (events.isEmpty()) return
    val padX = 6f
    val padY = 3f
    val gap = 8f
    val rowRight = ArrayList<Float>() // right edge of last pill per row
    var maxRows = 1

    data class Pill(val e: LoggedEvent, val x: Float, val left: Float, val w: Float, val h: Float, val text: TextLayoutResult, val row: Int)
    val pills = events.map { e ->
        // Black or white per pill from the fill's luminance — fixed white was ~1.9:1 on the
        // amber carbs pill. 0.179 is the luminance at which black and white text contrast
        // equally (WCAG contrast math), so this always picks the higher-contrast of the two.
        val fill = eventColor(e.category)
        val onFill = if (fill.luminance() > 0.179f) Color.Black else Color.White
        val text = tm.measure(eventChipLabel(e), TextStyle(color = onFill, fontSize = 9.sp, fontWeight = FontWeight.SemiBold))
        val w = text.size.width + padX * 2
        val h = text.size.height + padY * 2
        val x = xOf(e)
        // Not coerceIn: a pill wider than the plot would make it throw (min > max); this pins
        // the left edge inside the plot and lets the right edge overflow instead.
        val left = (x - w / 2).coerceAtMost(plotR - w).coerceAtLeast(plotL)
        var row = 0
        while (row < rowRight.size && left < rowRight[row] + gap) row++
        if (row < rowRight.size) rowRight[row] = left + w else rowRight.add(left + w)
        maxRows = max(maxRows, row + 1)
        Pill(e, x, left, w, h, text, row)
    }
    val rowH = (pills.firstOrNull()?.h ?: 16f) + 2f

    pills.forEach { p ->
        val c = eventColor(p.e.category)
        drawLine(c.copy(alpha = 0.85f), Offset(p.x, plotT), Offset(p.x, plotB), strokeWidth = 2f)
        val top = (plotT - 2f - (p.row + 1) * rowH).coerceAtLeast(0f)
        drawRoundRect(c, topLeft = Offset(p.left, top), size = Size(p.w, p.h), cornerRadius = CornerRadius(p.h / 2, p.h / 2))
        drawText(p.text, topLeft = Offset(p.left + padX, top + padY))
    }
}

private fun eventChipLabel(e: LoggedEvent): String {
    val u = e.category.unit
    val v = trimFloatValue(e.value)
    return if (u.isEmpty()) v else "$v$u"
}

private fun DrawScope.drawAxisLabel(
    tm: TextMeasurer,
    text: String,
    anchor: Offset,
    style: TextStyle,
    center: Boolean = false,
    alignEnd: Boolean = false,
) {
    val measured = tm.measure(text, style)
    val x = when {
        center -> anchor.x - measured.size.width / 2f
        alignEnd -> anchor.x - measured.size.width
        else -> anchor.x
    }
    val y = anchor.y - if (center || alignEnd) measured.size.height / 2f else 0f
    drawText(measured, topLeft = Offset(x, if (center) anchor.y else y))
}

/**
 * Inspector tooltip: theme-aware surface; the reading shown large with no unit and colored by
 * the glucose state (matching the big reading at the top of the screen). All events near the
 * inspector are listed.
 */
private fun DrawScope.drawTooltip(
    tm: TextMeasurer,
    point: GlucosePoint,
    unit: GlucoseUnit,
    showRaw: Boolean,
    showCal: Boolean,
    nearbyEvents: List<LoggedEvent>,
    trendAngle: Float,
    x: Float,
    plotT: Float,
    plotL: Float,
    plotR: Float,
    bg: Color,
    textColor: Color,
    valueColor: Color,
    rawColor: Color,
) {
    val time = tooltipDateFmt.format(Date(point.epochMillis))
    val rows = mutableListOf<TextLayoutResult>()
    rows += tm.measure(time, TextStyle(color = textColor, fontSize = 10.sp))
    val bigVal = if (showCal) point.calibratedMgdl else point.rawMgdl
    val valueRowIndex = rows.size
    rows += tm.measure(unit.format(bigVal), TextStyle(color = valueColor, fontSize = 22.sp, fontWeight = FontWeight.Bold))
    if (showCal && showRaw) {
        rows += tm.measure(unit.format(point.rawMgdl), TextStyle(color = rawColor, fontSize = 13.sp))
    }
    nearbyEvents.forEach { e ->
        val u = e.category.unit
        val label = "${e.label.ifEmpty { "Event" }}: ${trimFloatValue(e.value)}${if (u.isEmpty()) "" else " $u"}"
        rows += tm.measure(label, TextStyle(color = eventColor(e.category), fontSize = 12.sp, fontWeight = FontWeight.Medium))
    }

    val padX = 12f
    val padY = 8f
    val arrowSize = 26f
    // Reserve room for the trend arrow drawn to the right of the big value.
    val contentW = rows.mapIndexed { i, r ->
        if (i == valueRowIndex) r.size.width + arrowSize + 8f else r.size.width.toFloat()
    }.maxOrNull() ?: 0f
    val boxW = contentW + padX * 2
    val boxH = rows.sumOf { it.size.height } + padY * 2
    val bx = if (x + 12f + boxW <= plotR) x + 12f else max(plotL, x - 12f - boxW)
    val by = plotT + 2f
    drawRoundRect(bg, topLeft = Offset(bx, by), size = Size(boxW, boxH), cornerRadius = CornerRadius(14f, 14f))
    var ly = by + padY
    rows.forEachIndexed { i, r ->
        drawText(r, topLeft = Offset(bx + padX, ly))
        if (i == valueRowIndex) {
            val ax = bx + padX + r.size.width + 8f + arrowSize / 2
            val ay = ly + r.size.height / 2f
            drawTrendArrow(Offset(ax, ay), arrowSize, trendAngle, valueColor)
        }
        ly += r.size.height
    }
}

/** A small trend arrow (matching the home arrow) for the tooltip. */
private fun DrawScope.drawTrendArrow(center: Offset, size: Float, angleDeg: Float, color: Color) {
    val sw = size * 0.13f
    rotate(degrees = angleDeg, pivot = center) {
        val cy = center.y
        val left = center.x - size * 0.34f
        val tip = center.x + size * 0.36f
        drawLine(color, Offset(left, cy), Offset(tip, cy), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val back = center.x + size * 0.05f
        val spread = size * 0.24f
        drawLine(color, Offset(tip, cy), Offset(back, cy - spread), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, Offset(tip, cy), Offset(back, cy + spread), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

private fun trimFloatValue(v: Float): String =
    if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()

private fun niceStep(target: Double): Double {
    if (target <= 0) return 1.0
    val mag = 10.0.pow(floor(kotlin.math.log10(target)))
    val norm = target / mag
    val nice = when {
        norm < 1.5 -> 1.0
        norm < 3.0 -> 2.0
        norm < 7.0 -> 5.0
        else -> 10.0
    }
    return nice * mag
}

private fun niceTimeStep(duration: Long): Long {
    val h = 60 * 60 * 1000L
    return when {
        duration <= 1 * h -> 15 * 60 * 1000L
        duration <= 3 * h -> 30 * 60 * 1000L
        duration <= 6 * h -> h
        duration <= 12 * h -> 2 * h
        duration <= 24 * h -> 4 * h
        else -> 12 * h
    }
}

// Formatters are reused rather than re-allocated: the whole Canvas redraws every inspector-drag
// frame, so building a SimpleDateFormat per draw was needless churn. Single-threaded (main) draw,
// so sharing them is safe.
private val tooltipDateFmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
private val axisTimeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val axisDayFmt = SimpleDateFormat("EEE HH", Locale.getDefault())

private fun timeFormatter(duration: Long): SimpleDateFormat =
    if (duration <= 24 * 60 * 60 * 1000L) axisTimeFmt else axisDayFmt

private fun formatAxis(displayValue: Double, unit: GlucoseUnit): String = when (unit) {
    GlucoseUnit.MGDL -> displayValue.toInt().toString()
    GlucoseUnit.MMOLL -> String.format("%.1f", displayValue)
}
