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
package de.mogluco.wear.service

import android.content.Context
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import de.mogluco.data.GlucosePoint
import de.mogluco.data.GlucoseReading
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.TrendArrow
import de.mogluco.wear.WearApp

/**
 * Last-known glucose state for the render surfaces that live outside the app's composition
 * (complications, tile, ongoing chip). [WearGlucoseService] refreshes it on every reading;
 * [read] falls back to asking the repository directly (first emission, bounded) when the
 * process was just recreated and the service hasn't caught up yet.
 */
object GlucoseSnapshot {

    /** Readings older than this render as "--" (legacy Notify.glucosetimeout = 5.5 min). */
    const val STALE_MILLIS = 330_000L

    data class Snapshot(
        val reading: GlucoseReading?,
        val history: List<GlucosePoint>,
        val unit: GlucoseUnit,
        val targetLow: Double,
        val targetHigh: Double,
    ) {
        fun isUsable(now: Long = System.currentTimeMillis()): Boolean =
            reading != null && !reading.isStale(now, STALE_MILLIS)

        /** Display text for the value, or "--" when there's no fresh reading. */
        fun valueText(now: Long = System.currentTimeMillis()): String =
            if (isUsable(now)) unit.format(reading!!.calibratedMgdl) else "--"

        fun trend(now: Long = System.currentTimeMillis()): TrendArrow =
            if (isUsable(now)) reading!!.trend else TrendArrow.UNKNOWN
    }

    @Volatile
    var current: Snapshot? = null
        private set

    fun update(snapshot: Snapshot) {
        current = snapshot
    }

    /**
     * The current snapshot, or a repository read when the cache is cold. The repository's
     * `latest` may legitimately be null (no data yet) — that still produces a snapshot (with
     * a null reading) so callers render the honest "--".
     */
    suspend fun read(context: Context): Snapshot {
        current?.let { return it }
        val repo = WearApp.from(context).glucoseRepository
        val prefs = WearApp.from(context).preferences
        val reading = withTimeoutOrNull(2_000) { repo.latest.firstOrNull() }
        val history = withTimeoutOrNull(1_000) { repo.history.firstOrNull() } ?: emptyList()
        // With no live reading yet (fresh process), the stored history's tail is the most
        // recent known value — surfaced with an UNKNOWN trend rather than a fabricated rate.
        val effective = reading ?: history.lastOrNull()?.let {
            GlucoseReading(
                calibratedMgdl = it.calibratedMgdl,
                rawMgdl = it.rawMgdl,
                epochMillis = it.epochMillis,
                trend = TrendArrow.UNKNOWN,
            )
        }
        val snapshot = Snapshot(
            reading = effective,
            history = history,
            unit = prefs.unit.value,
            targetLow = prefs.targetLow.value.toDouble(),
            targetHigh = prefs.targetHigh.value.toDouble(),
        )
        current = snapshot
        return snapshot
    }
}
