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
package de.mogluco.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.TimeZone

/**
 * Local-calendar day helpers shared by the calendar screens and Settings.
 *
 * A "day" value is the epoch millis of a LOCAL midnight. All day arithmetic goes through
 * [Calendar]/[java.time] fields — never fixed `24 * 60 * 60 * 1000` millis — because DST
 * transition days are 23 or 25 hours long, and fixed-24h math silently shifts every date and
 * window after a transition.
 */

/** Local midnight of the day containing [millis]. */
fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * The local midnight FOLLOWING [dayStartMillis]. DST-safe: a fall-back day keeps its 25th hour
 * (and a spring-forward day its 23), where `start + 24h` lands an hour off for every day after
 * the transition.
 */
fun nextLocalMidnight(dayStartMillis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = dayStartMillis
    add(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** [delta] whole local calendar days from [dayStartMillis], preserving the midnight wall time. */
fun addDays(dayStartMillis: Long, delta: Int): Long = Calendar.getInstance().apply {
    timeInMillis = dayStartMillis
    add(Calendar.DAY_OF_MONTH, delta)
}.timeInMillis

/**
 * Whole local calendar days from [fromMillis]'s day to [toMillis]'s day (0 = same day, negative
 * when [toMillis] is earlier). Counted via [ChronoUnit.DAYS] over local dates, so DST days count
 * as exactly one day regardless of their length.
 */
fun localDaysBetween(fromMillis: Long, toMillis: Long): Int =
    ChronoUnit.DAYS.between(toLocalDate(fromMillis), toLocalDate(toMillis)).toInt()

private fun toLocalDate(millis: Long): java.time.LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

// The Material DatePicker works in UTC-midnight millis; the app's day math works in
// local-midnight millis. These convert the calendar date across, preserving Y/M/D so the day
// never shifts — using the picker's value raw is a full day off in UTC-negative zones and a
// mid-day boundary everywhere else.

/** A local-midnight day → the same calendar date as UTC midnight (the DatePicker's domain). */
fun localDayToUtc(localMillis: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = localMillis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** A DatePicker UTC-midnight value → the same calendar date as a local midnight. */
fun utcToLocalDay(utcMillis: Long): Long {
    val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear(); set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}
