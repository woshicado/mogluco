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

/**
 * Discrete graph time windows the user can pick between. [TODAY] and [YESTERDAY] are special: their
 * windows align to local calendar-day boundaries (not a fixed duration before now), so their
 * [millis] is a sentinel — the caller computes the window start/end for them explicitly.
 */
enum class TimeRange(val label: String, val millis: Long) {
    H6("6h", 6 * 60 * 60 * 1000L),
    H12("12h", 12 * 60 * 60 * 1000L),
    D1("1d", 24 * 60 * 60 * 1000L),
    TODAY("Today", -1L),
    YESTERDAY("Yesterday", -2L),
}
