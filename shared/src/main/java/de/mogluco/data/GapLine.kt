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
package de.mogluco.data

/**
 * Walk [points] in order and emit a polyline through [moveTo]/[lineTo], starting a new subpath at the
 * first point and wherever the time gap to the previous point exceeds [gapMillis] (when > 0) — so a
 * long no-data stretch isn't bridged by a straight interpolating line.
 *
 * Path-type agnostic on purpose: the phone chart, the watch chart, and the notification/AOD bitmap
 * renderer draw with three different Path types (two Compose, one android.graphics). Each passes its
 * own path's `moveTo`/`lineTo` plus coordinate mappers, so the gap-break rule lives in exactly one
 * place and the three surfaces can't drift apart. Inline, so the lambdas cost nothing.
 */
inline fun buildGapLine(
    points: List<GlucosePoint>,
    gapMillis: Long,
    xOf: (GlucosePoint) -> Float,
    yOf: (GlucosePoint) -> Float,
    moveTo: (Float, Float) -> Unit,
    lineTo: (Float, Float) -> Unit,
) {
    var prevT = Long.MIN_VALUE
    for (p in points) {
        val x = xOf(p)
        val y = yOf(p)
        if (prevT == Long.MIN_VALUE || (gapMillis > 0L && p.epochMillis - prevT > gapMillis)) {
            moveTo(x, y)
        } else {
            lineTo(x, y)
        }
        prevT = p.epochMillis
    }
}
