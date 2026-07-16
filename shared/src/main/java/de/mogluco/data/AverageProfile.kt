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

import java.time.Instant
import java.time.ZoneId
import kotlin.math.sqrt

/** Per-hour-of-day aggregate: mean and standard deviation of every reading that fell in this hour. */
data class HourStat(val hour: Int, val mean: Double, val std: Double, val count: Int)

/**
 * A time-of-day glucose profile over a date range (the Libre reader's "average day" view). For each
 * hour 0..23 it holds the mean and standard deviation of every reading in that hour across the whole
 * range, plus the four 6-hour section means (00–06, 06–12, 12–18, 18–24) and the overall mean. All
 * values are mg/dL; the UI converts to the display unit. Pure/allocation-light so it can recompute
 * as the range changes.
 */
data class AverageProfile(
    val hourly: List<HourStat>,       // exactly 24, ascending by hour (count 0 ⇒ mean/std 0)
    val sectionMeans: List<Double?>,  // 4: [00–06, 06–12, 12–18, 18–24], null when the section has no data
    val overallMean: Double?,         // null when the range has no readings
    val totalCount: Int,
) {
    companion object {
        /** Hour boundaries of the four sections: [0,6), [6,12), [12,18), [18,24). */
        val SECTION_BOUNDS = intArrayOf(0, 6, 12, 18, 24)

        fun from(
            history: List<GlucosePoint>,
            startMillis: Long,
            endMillis: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): AverageProfile {
            val window = history.windowed(startMillis, endMillis)
            // One pass: sum and sum-of-squares per local hour give mean and std cheaply.
            val n = IntArray(24)
            val sum = DoubleArray(24)
            val sumSq = DoubleArray(24)
            for (p in window) {
                val hour = Instant.ofEpochMilli(p.epochMillis).atZone(zone).hour
                val v = p.calibratedMgdl
                n[hour]++
                sum[hour] += v
                sumSq[hour] += v * v
            }
            val hourly = (0 until 24).map { h ->
                if (n[h] == 0) {
                    HourStat(h, 0.0, 0.0, 0)
                } else {
                    val mean = sum[h] / n[h]
                    val variance = (sumSq[h] / n[h] - mean * mean).coerceAtLeast(0.0)
                    HourStat(h, mean, sqrt(variance), n[h])
                }
            }
            val sectionMeans = (0 until 4).map { s ->
                var cnt = 0
                var tot = 0.0
                for (h in SECTION_BOUNDS[s] until SECTION_BOUNDS[s + 1]) {
                    cnt += n[h]
                    tot += sum[h]
                }
                if (cnt == 0) null else tot / cnt
            }
            val totalCount = n.sum()
            val overallMean = if (totalCount == 0) null else sum.sum() / totalCount
            return AverageProfile(hourly, sectionMeans, overallMean, totalCount)
        }
    }
}
