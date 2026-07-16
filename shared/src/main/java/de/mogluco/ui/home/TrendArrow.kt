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
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.mogluco.data.GlucoseReading

/**
 * The glucose trend arrow, drawn by hand so it has the look the design calls for: a short,
 * chunky shaft with rounded caps and a wide, rounded arrowhead (rather than a long thin
 * arrow). It's rotated by the reading's rate-derived angle, so a flat trend points right and
 * rises/falls tilt up/down. Shared by the home header, the reading list, and the calendar so
 * the glyph and angle are always identical.
 */
@Composable
fun TrendArrow(
    reading: GlucoseReading,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    // Canvas draws are invisible to TalkBack — expose the trend direction as text.
    val description = "Trend: ${reading.trend.name.lowercase().replace('_', ' ')}"
    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        val s = this.size.minDimension
        val sw = s * TrendArrowShape.STROKE
        rotate(degrees = reading.trendAngleDegrees, pivot = center) {
            TrendArrowShape.strokes(s) { x1, y1, x2, y2 ->
                drawLine(tint, Offset(x1, y1), Offset(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
    }
}

/**
 * The trend-arrow geometry as fractions of the icon side, kept in one place so this Compose
 * composable, the wear complication bitmap ([de.mogluco.wear.complication.ArrowBitmap]), and the
 * AOD overlay bitmap all draw the identical glyph. Callers own rotation and stroke styling.
 */
object TrendArrowShape {
    /** Stroke width as a fraction of the icon side. */
    const val STROKE = 0.125f

    /** Emit the three strokes (shaft + two arrowhead fans) for icon side [size] via [line]. */
    inline fun strokes(size: Float, line: (Float, Float, Float, Float) -> Unit) {
        val cy = size / 2f
        val left = size * 0.16f
        val tip = size * 0.86f
        val back = size * 0.55f
        val spread = size * 0.24f
        line(left, cy, tip, cy)
        line(tip, cy, back, cy - spread)
        line(tip, cy, back, cy + spread)
    }
}
