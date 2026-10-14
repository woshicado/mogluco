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
import kotlinx.coroutines.flow.first
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
 * [read] falls back to asking the repository directly (first emission, bounded) while the
 * service has none published — the process was just recreated, the system refused to start
 * the service from the background, or stopped it again while the process lives on.
 */
object GlucoseSnapshot {

    /** Readings older than this render as "--" (legacy Notify.glucosetimeout = 5.5 min). */
    const val STALE_MILLIS = 330_000L

    /** How long a cold [read] waits for the stored history (the tile asks on the main thread). */
    private const val HISTORY_WAIT_MS = 1_000L

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

        /**
         * The last instant [reading] counts as usable. Complications and the tile hand this to
         * the system as the end of a live value's validity, so the flip to "--" follows the wall
         * clock even while the watch is suspended and nothing in this process runs.
         */
        val usableUntilMillis: Long?
            get() = reading?.let { it.epochMillis + STALE_MILLIS }
    }

    @Volatile
    var current: Snapshot? = null
        private set

    fun update(snapshot: Snapshot) {
        current = snapshot
    }

    /**
     * The service is gone and no longer keeps [current] up to date. The process can outlive it
     * (a service the system refused foreground state is stopped once idle, while complication
     * and tile requests and the phone link keep the process), and readings keep arriving in it:
     * left in place, its last snapshot — the cold "--" right after a process start — would be
     * what [read] answers with from then on.
     */
    fun clear() {
        current = null
    }

    /**
     * With no live reading yet (fresh process), the stored history's tail is the most recent
     * known value — surfaced with an UNKNOWN trend rather than a fabricated rate, and with its
     * own time, so an old tail renders as stale ("--") like any other old reading.
     */
    fun tailReading(history: List<GlucosePoint>): GlucoseReading? = history.lastOrNull()?.let {
        GlucoseReading(
            // The measured value: a history point's calibratedMgdl is the smoothed (EMA)
            // trace, which must not stand in for the current reading.
            calibratedMgdl = it.rawMgdl,
            rawMgdl = it.rawMgdl,
            epochMillis = it.epochMillis,
            trend = TrendArrow.UNKNOWN,
        )
    }

    /**
     * The current snapshot, or a repository read when the cache is cold. The repository's
     * `latest` may legitimately be null (no data yet) — that still produces a snapshot (with
     * a null reading) so callers render the honest "--".
     *
     * The repository read is deliberately not stored in [current]: right after process start
     * the flows still hold their cold values (null / empty, history not loaded yet), and keeping
     * those would pin every surface to "--" for the life of a process in which the service is
     * not running. Only the service's [update] fills the cache.
     */
    suspend fun read(context: Context): Snapshot {
        val repo = WearApp.from(context).glucoseRepository
        val prefs = WearApp.from(context).preferences
        val published = current
        val publishedAt = published?.reading?.epochMillis
        // The service's snapshot only while it is up to date: one that is older than the
        // repository's latest reading is not being refreshed (any more, or not yet). Nor one
        // without a reading — the service's first after a process start, published before the
        // stored history has loaded: what the repository holds by now is asked below.
        if (published != null && publishedAt != null && repo.currentReadingMillis <= publishedAt) {
            return published
        }
        // Nothing published: the service is not running. It is what pushes each new value to
        // these surfaces, so ask for it again with the request that found it missing — while
        // the watch face or the tile host is bound to us the system may allow the start that
        // it refused at process start. A refusal is caught in start() and changes nothing.
        if (published == null) WearGlucoseService.start(context)
        val reading = repo.latest.firstOrNull()
        // No live reading yet: in a process that has only just started the stored history is
        // still being read (off the main thread), and asked at once it is empty — a request that
        // itself started the process would then be answered with "--" although the store holds
        // a fresh value, and with no service running nothing corrects that before the next poll.
        // So give the store its moment, bounded: the surfaces have their own deadlines.
        if (reading == null) withTimeoutOrNull(HISTORY_WAIT_MS) { repo.historyLoaded.first { it } }
        val history = repo.history.firstOrNull() ?: emptyList()
        return Snapshot(
            reading = reading ?: tailReading(history),
            history = history,
            unit = prefs.unit.value,
            targetLow = prefs.targetLow.value.toDouble(),
            targetHigh = prefs.targetHigh.value.toDouble(),
        )
    }
}
