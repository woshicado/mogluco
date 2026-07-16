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
package de.mogluco.wear.complication

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import de.mogluco.data.GlucoseReading
import de.mogluco.data.TrendArrow
import de.mogluco.ui.home.TrendArrowShape

/**
 * Renders the app's hand-drawn trend arrow (same geometry as :shared's TrendArrow
 * composable — chunky shaft, wide rounded head) into a bitmap for complication images.
 * White on transparent: watch faces tint monochromatic images themselves.
 */
object ArrowBitmap {

    fun render(sizePx: Int, angleDegrees: Float, color: Int = android.graphics.Color.WHITE): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = sizePx.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = s * TrendArrowShape.STROKE
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        c.save()
        c.rotate(angleDegrees, s / 2f, s / 2f)
        TrendArrowShape.strokes(s) { x1, y1, x2, y2 -> c.drawLine(x1, y1, x2, y2, paint) }
        c.restore()
        return bmp
    }

    /** A question-dot glyph for UNKNOWN trend / stale data. */
    fun renderUnknown(sizePx: Int, color: Int = android.graphics.Color.WHITE): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawCircle(sizePx / 2f, sizePx / 2f, sizePx * 0.08f, paint)
        return bmp
    }

    fun forReading(sizePx: Int, reading: GlucoseReading?, color: Int = android.graphics.Color.WHITE): Bitmap =
        if (reading == null || reading.trend == TrendArrow.UNKNOWN) {
            renderUnknown(sizePx, color)
        } else {
            render(sizePx, reading.trendAngleDegrees, color)
        }
}
