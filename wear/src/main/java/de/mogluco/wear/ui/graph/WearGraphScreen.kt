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
package de.mogluco.wear.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import de.mogluco.data.GlucosePoint
import de.mogluco.data.GlucoseRepository
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.buildGapLine
import de.mogluco.data.windowed
import de.mogluco.ui.home.TimeRange
import de.mogluco.ui.home.TrendArrow
import de.mogluco.wear.ui.home.ageText
import de.mogluco.ui.theme.GlucoseColors
import de.mogluco.ui.theme.colorFor
import de.mogluco.wear.ui.WearMoglucoTheme
import de.mogluco.wear.ui.rememberLifecycleTicker
import kotlin.math.sqrt

/** Time windows offered on the watch (tap the chart to cycle). */
private val WATCH_RANGES = listOf(TimeRange.H6, TimeRange.H12, TimeRange.D1)

/** The screen's padding above and beside its content; the chart's round-screen inset counts from them. */
private val TOP_PADDING = 20.dp
private val SIDE_PADDING = 6.dp

/**
 * Graph screen (tile-styled): everything on one screen, no scrolling — a big state-colored value +
 * trend arrow, "N min ago", the tile's chart (target band, smoothed trace, latest dot), and a
 * "tap to change range" line. Tap the chart to cycle 6h → 12h → 1d.
 */
@Composable
fun WearGraphScreen(repository: GlucoseRepository) {
    val reading by repository.latest.collectAsState(initial = null)
    val unit by repository.unit.collectAsState(initial = GlucoseUnit.MGDL)
    val targetRange by repository.targetRange.collectAsState(initial = 70.0..180.0)
    val history by repository.history.collectAsState(initial = emptyList())
    var rangeIndex by remember { mutableStateOf(0) }
    val range = WATCH_RANGES[rangeIndex]
    val context = LocalContext.current
    val gapMin by de.mogluco.wear.WearApp.from(context).preferences.gapThresholdMinutes.collectAsState()

    // The window slides with time (and staleness needs re-evaluation), tick each 30 s.
    // Lifecycle-gated: stops while the app sits paused behind the watchface.
    val now by rememberLifecycleTicker(30_000)

    val glucose = WearMoglucoTheme.glucose

    // A round screen cuts off the chart's lower corners — on the right that is where the newest
    // part of a low trace and the latest dot are drawn. How much depends on where the chart ends
    // up (the text above it moves it), so that is taken from the layout: its top within the
    // column, in px; NaN until it is placed.
    val configuration = LocalConfiguration.current
    val screenRound = configuration.isScreenRound
    val screenWidth = configuration.screenWidthDp.dp
    var chartTop by remember { mutableFloatStateOf(Float.NaN) }

    ScreenScaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = TOP_PADDING, bottom = 8.dp, start = SIDE_PADDING, end = SIDE_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val r = reading
            if (r != null) {
                val color = if (r.isStale(now)) glucose.stale else glucose.colorFor(
                    r.state(targetRange.start, targetRange.endInclusive),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        unit.format(r.calibratedMgdl),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp),
                        color = color,
                    )
                    TrendArrow(reading = r, tint = color, size = 26.dp)
                }
                Text(
                    ageText(r.ageMillis(now)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Waiting for data", style = MaterialTheme.typography.titleMedium)
            }
            WearGlucoseChart(
                points = history,
                unit = unit,
                targetLow = targetRange.start,
                targetHigh = targetRange.endInclusive,
                windowEnd = now,
                windowMillis = range.millis,
                glucose = glucose,
                lineColor = MaterialTheme.colorScheme.primary,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                gapMillis = gapMin * 60_000L,
                // Every row of the chart stays inside the circle: the row farthest from the
                // screen's centre decides (the bottom one, as the chart sits below it), less what
                // the screen's own padding already keeps clear. The tap target keeps its width.
                sideInset = {
                    if (!screenRound || chartTop.isNaN()) {
                        0f
                    } else {
                        val diameter = screenWidth.toPx()
                        val top = TOP_PADDING.toPx() + chartTop
                        (maxOf(
                            roundScreenInset(diameter, top),
                            roundScreenInset(diameter, top + size.height),
                        ) - SIDE_PADDING.toPx()).coerceAtLeast(0f)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .onPlaced { chartTop = it.positionInParent().y }
                    .clickable { rangeIndex = (rangeIndex + 1) % WATCH_RANGES.size },
            )
            Text(
                "${range.label} · tap to cycle",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * How far the row [y] below the top of a round screen of [diameter] has to keep from the screen's
 * left and right edges to lie inside the circle (one unit throughout): the radius less half the
 * chord at that row. 204 dp across, 66 dp below the centre: 102 − √(102² − 66²) ≈ 24 dp.
 */
internal fun roundScreenInset(diameter: Float, y: Float): Float {
    val radius = diameter / 2f
    val fromCentre = (y - radius).coerceIn(-radius, radius)
    return radius - sqrt(radius * radius - fromCentre * fromCentre)
}

/**
 * Minimal watch chart: target band (in-range green at low alpha), calibrated line, latest
 * point dot, min/max scale labels. No inspector, no events, no dual raw/cal traces — those
 * live on the phone.
 *
 * [sideInset] = room to leave empty on both sides (px, read while drawing): the label gutter and
 * the plot move in by it, so nothing is drawn where a round screen has no pixels.
 */
@Composable
fun WearGlucoseChart(
    points: List<GlucosePoint>,
    unit: GlucoseUnit,
    targetLow: Double,
    targetHigh: Double,
    windowEnd: Long,
    windowMillis: Long,
    glucose: GlucoseColors,
    lineColor: Color,
    labelColor: Color,
    gapMillis: Long = 0L,
    sideInset: DrawScope.() -> Float = { 0f },
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = labelColor)
    val windowStart = windowEnd - windowMillis
    // Hoist the windowed slice + y-domain out of the Canvas draw lambda so they're computed once per
    // data/window change, not on every draw pass (the 30s tick, unit/target/theme recompositions).
    val pts = remember(points, windowStart, windowEnd) { points.windowed(windowStart, windowEnd) }
    val (yMin, yMax) = remember(pts, targetLow, targetHigh) {
        // Y domain: data extremes padded, always containing the target band.
        val dataMin = pts.minOfOrNull { it.calibratedMgdl } ?: targetLow
        val dataMax = pts.maxOfOrNull { it.calibratedMgdl } ?: targetHigh
        minOf(dataMin - 15, targetLow - 25) to maxOf(dataMax + 15, targetHigh + 35)
    }
    Canvas(modifier) {
        // Never more than a quarter of the width per side: a plot must remain.
        val side = sideInset().coerceIn(0f, size.width / 4f)
        val leftPad = side + 26.dp.toPx() // label gutter
        val plot = Rect(leftPad, 4.dp.toPx(), size.width - side - 2.dp.toPx(), size.height - 4.dp.toPx())
        fun x(t: Long) = plot.left + (t - windowStart).toFloat() / windowMillis * plot.width
        fun y(v: Double) = (plot.bottom - ((v - yMin) / (yMax - yMin)).toFloat() * plot.height)

        // Target band.
        val bandTop = y(targetHigh)
        val bandBot = y(targetLow)
        drawRect(
            color = glucose.inRange.copy(alpha = 0.16f),
            topLeft = Offset(plot.left, bandTop),
            size = Size(plot.width, bandBot - bandTop),
        )
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
        drawLine(glucose.inRange.copy(alpha = 0.5f), Offset(plot.left, bandTop), Offset(plot.right, bandTop), 1.dp.toPx(), pathEffect = dash)
        drawLine(glucose.inRange.copy(alpha = 0.5f), Offset(plot.left, bandBot), Offset(plot.right, bandBot), 1.dp.toPx(), pathEffect = dash)

        // Band bound labels in the gutter.
        listOf(targetHigh to bandTop, targetLow to bandBot).forEach { (v, py) ->
            val layout = textMeasurer.measure(unit.format(v), labelStyle)
            drawText(
                layout,
                topLeft = Offset(leftPad - layout.size.width - 4.dp.toPx(), py - layout.size.height / 2f),
            )
        }

        // Smoothed trace + latest dot (broken across gaps longer than the threshold).
        if (pts.size >= 2) {
            val path = Path()
            buildGapLine(pts, gapMillis, { x(it.epochMillis) }, { y(it.calibratedMgdl) }, { px, py -> path.moveTo(px, py) }, { px, py -> path.lineTo(px, py) })
            drawPath(
                path,
                color = lineColor,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        pts.lastOrNull()?.let { last ->
            drawCircle(lineColor, radius = 3.5.dp.toPx(), center = Offset(x(last.epochMillis), y(last.calibratedMgdl)))
        }
        if (pts.isEmpty()) {
            val layout = textMeasurer.measure("No data in range", labelStyle)
            drawText(
                layout,
                topLeft = Offset(
                    plot.left + (plot.width - layout.size.width) / 2f,
                    plot.top + (plot.height - layout.size.height) / 2f,
                ),
            )
        }
    }
}
