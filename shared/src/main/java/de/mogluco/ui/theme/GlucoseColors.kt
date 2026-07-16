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
package de.mogluco.ui.theme

import androidx.compose.ui.graphics.Color
import de.mogluco.data.GlucoseState

/**
 * Glucose-state colors — the glucose-semantic tokens shared by the phone and the watch.
 * Independent of light/dark primary so a "low" always reads as a low regardless of theme.
 * Tuned for color-vision accessibility (low = magenta, high = amber — not the red/green
 * pair that fails for many users).
 */
data class GlucoseColors(
    val urgentLow: Color,
    val low: Color,
    val inRange: Color,
    val high: Color,
    val urgentHigh: Color,
    val onState: Color,
    val stale: Color,
)

/** The color for a glucose state. Shared by the reading card and the chart band coloring. */
fun GlucoseColors.colorFor(state: GlucoseState): Color = when (state) {
    GlucoseState.URGENT_LOW -> urgentLow
    GlucoseState.LOW -> low
    GlucoseState.IN_RANGE -> inRange
    GlucoseState.HIGH -> high
    GlucoseState.URGENT_HIGH -> urgentHigh
}

val LightGlucoseColors = GlucoseColors(
    urgentLow = Color(0xFFC2185B),
    low = Color(0xFFE5436F),
    inRange = Color(0xFF2E9E6B),
    high = Color(0xFFE8920C),
    urgentHigh = Color(0xFFD2691E),
    onState = Color(0xFFFFFFFF),
    stale = Color(0xFF9AA5A3),
)

val DarkGlucoseColors = GlucoseColors(
    urgentLow = Color(0xFFFF7AA8),
    low = Color(0xFFFF8FB0),
    inRange = Color(0xFF52D89A),
    high = Color(0xFFFFB74D),
    urgentHigh = Color(0xFFFFA05C),
    onState = Color(0xFF0E1514),
    stale = Color(0xFF6B7674),
)
