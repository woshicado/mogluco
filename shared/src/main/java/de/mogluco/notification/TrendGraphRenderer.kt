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
package de.mogluco.notification

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import de.mogluco.data.GlucosePoint
import de.mogluco.data.buildGapLine
import de.mogluco.data.windowed

/**
 * Shared trend line-graph renderer.
 *
 * Both the ongoing notification (lock-screen big-picture + mini large-icon) and the
 * always-on-display overlay draw the same calibrated-trace line graph; keeping the drawing math
 * in one place means a visual tweak can't make the two drift apart. Pure function: it takes the
 * points plus sizing/colours and returns a bitmap, or null when fewer than two points fall inside
 * the window. Callers pass their own dimensions/colours so each surface keeps its exact look.
 */
object TrendGraphRenderer {

    /** The app's trend-line teal, shared by the notification graph and the AOD overlay. */
    const val TEAL = "#0E7C7B"

    fun lineGraph(
        history: List<GlucosePoint>,
        nowMillis: Long,
        windowMillis: Long,
        widthPx: Int,
        heightPx: Int,
        padPx: Float,
        strokePx: Float,
        dotPx: Float,
        lineColor: Int = Color.parseColor(TEAL),
        gridStrokePx: Float = 0f, // <= 0 → no grid
        // Target band (mg/dL): drawn as a translucent horizontal band only when both bounds are
        // > 0 and low < high. Default 0 → no band, so the notification/AOD callers are unchanged.
        targetLow: Double = 0.0,
        targetHigh: Double = 0.0,
        bandColor: Int = 0x2600C853, // ~15% green
        gapMillis: Long = 0L, // > 0 → break the trace across a gap longer than this
    ): Bitmap? {
        val start = nowMillis - windowMillis
        // Binary-search slice, not a full-list filter: callers may pass the entire history
        // (~300k points) and this renders on every notification/AOD refresh.
        val pts = history.windowed(start, nowMillis)
        if (pts.size < 2 || widthPx < 2 || heightPx < 2) return null
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hasBand = targetLow > 0.0 && targetHigh > targetLow
        val dataMin = pts.minOf { it.calibratedMgdl }
        val dataMax = pts.maxOf { it.calibratedMgdl }
        // Soft y-domain: always span the target band (plus a little breathing room so it isn't flush
        // against the edges), expanding only when readings run beyond it. A stable scale anchored on
        // the band makes it easy to judge how big a jump really was, instead of a fully-dynamic axis
        // that rescales to whatever the recent window happens to hold.
        val minV: Double
        val maxV: Double
        if (hasBand) {
            val margin = ((targetHigh - targetLow) * 0.15).coerceAtLeast(10.0)
            minV = minOf(dataMin, targetLow - margin)
            maxV = maxOf(dataMax, targetHigh + margin)
        } else {
            minV = dataMin
            maxV = dataMax
        }
        val span = (maxV - minV).coerceAtLeast(1.0)
        fun x(t: Long) = padPx + ((t - start).toFloat() / (nowMillis - start)) * (widthPx - 2 * padPx)
        fun y(v: Double) = (heightPx - padPx) - (((v - minV) / span).toFloat()) * (heightPx - 2 * padPx)
        if (hasBand) {
            // Behind the grid and trace: the in-range zone as a filled band.
            c.drawRect(padPx, y(targetHigh), widthPx - padPx, y(targetLow),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bandColor; style = Paint.Style.FILL })
        }
        if (gridStrokePx > 0f) {
            val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#33888888"); strokeWidth = gridStrokePx
            }
            for (i in 1..3) {
                val gy = padPx + (heightPx - 2 * padPx) * i / 4f
                c.drawLine(padPx, gy, widthPx - padPx, gy, grid)
            }
        }
        val path = Path()
        buildGapLine(pts, gapMillis, { x(it.epochMillis) }, { y(it.calibratedMgdl) }, { px, py -> path.moveTo(px, py) }, { px, py -> path.lineTo(px, py) })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = strokePx
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = lineColor
        })
        val last = pts.last()
        c.drawCircle(x(last.epochMillis), y(last.calibratedMgdl), dotPx,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor })
        return bmp
    }
}
