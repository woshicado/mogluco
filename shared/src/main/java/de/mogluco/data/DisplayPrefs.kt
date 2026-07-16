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

import kotlinx.coroutines.flow.StateFlow

/**
 * The display preferences the glucose repositories mirror. Both apps implement it over their
 * own preference store (phone: `AppPreferences`, watch: `WearPreferences`) so the shared
 * repositories stay decoupled from either app's full settings surface.
 */
interface DisplayPrefs {
    /** Display unit (mg/dL or mmol/L). */
    val unit: StateFlow<GlucoseUnit>

    /** Target band low bound, mg/dL. */
    val targetLow: StateFlow<Float>

    /** Target band high bound, mg/dL. */
    val targetHigh: StateFlow<Float>

    /** Which readings to show (raw / smoothed / both). */
    val readingDisplay: StateFlow<ReadingDisplay>

    /** Whether the smoothed trace is derived (EMA of raw); false = smoothed equals raw. */
    val smoothing: StateFlow<Boolean>

    /** EMA weight for the smoothed trace (0..1): higher = follows raw more closely (less smoothing). */
    val smoothingAlpha: StateFlow<Float>

    /** Max minutes between two readings still joined by a line; a longer gap breaks the trace. */
    val gapThresholdMinutes: StateFlow<Int>
}
