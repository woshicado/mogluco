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

import kotlinx.coroutines.flow.Flow

/**
 * The single source of truth the UI reads from.
 *
 * Phase 0 ships [MockGlucoseRepository]. In Phase 1 a `NativeGlucoseRepository` (in the
 * `:core`-backed wiring) will implement this over `tk.glucodata.Natives`, so no screen
 * code changes when the real engine is connected.
 */
interface GlucoseRepository {
    /** Live stream of the most recent reading; emits on each new measurement. */
    val latest: Flow<GlucoseReading?>

    /** Display unit preference. */
    val unit: Flow<GlucoseUnit>

    /** User target range, in mg/dL (low to high). */
    val targetRange: Flow<ClosedFloatingPointRange<Double>>

    /** Recent glucose history for the graph, oldest-first. */
    val history: Flow<List<GlucosePoint>>

    /**
     * Whether [history] holds what the store holds. False from the start of the process until the
     * stored readings have been read for the first time — until then an empty (or nearly empty)
     * [history] says nothing about what there is, and must not be taken for it (an export would
     * write a file without data). True from then on, also for a store that holds nothing.
     * Default: true — a repository that has its data from the start.
     */
    val historyLoaded: Flow<Boolean>
        get() = kotlinx.coroutines.flow.flowOf(true)

    /** The active sensor (id, activation, expiry, error), or null when none is running. */
    val sensor: Flow<SensorInfo?>

    /**
     * Every sensor the store remembers (past + present), oldest activation first. Lets the
     * calendar name the sensor that was active on a past day instead of only the current one.
     */
    val sensorHistory: Flow<List<SensorInfo>>
        get() = kotlinx.coroutines.flow.flowOf(emptyList())

    /**
     * A synchronous peek at the current [SensorInfo] (or null), for non-suspend callers such as
     * the alarm watchdog that must decide "is the sensor still warming up?" without collecting a
     * flow. Reflects the same value the [sensor] flow last emitted.
     */
    val currentSensor: SensorInfo?

    /**
     * A second, newly activated sensor whose setup is failing while [sensor] is still the previous
     * one (it keeps the current value, the alarms and the card until the new sensor delivers its
     * first reading): its id, activation and the failure in [SensorInfo.error]. Null when there is
     * no such sensor — always with a single active sensor — and again once it delivers, is the
     * one in [sensor], or is no longer in use. Default: none.
     */
    val newSensorFault: Flow<SensorInfo?>
        get() = kotlinx.coroutines.flow.flowOf(null)

    /**
     * Epoch millis of the latest reading (or 0), a synchronous peek matching the [latest] flow. Used
     * by non-Flow consumers such as the auto-reclaim monitor so its "watch went silent" clock uses
     * the same last-received-reading time the UI shows, not a coarser mirror sync marker.
     */
    val currentReadingMillis: Long get() = 0L

    /** Which readings the user wants shown (raw / calibrated / both). */
    val readingDisplay: Flow<ReadingDisplay>

    /**
     * Delete all glucose history strictly before [cutoffMillis].
     *
     * Abstract on purpose: every implementation must handle it, so the native path can't
     * silently inherit a no-op and make the UI report a deletion that never happened.
     *
     * @return true iff glucose data was actually deleted. The native path returns false —
     * the glucose store is engine-owned — so callers can report honestly.
     */
    fun deleteBefore(cutoffMillis: Long): Boolean

    /**
     * Replace the glucose history (used when restoring a backup). Clears any delete cutoff.
     *
     * @return true iff the history was actually imported (false on the engine-owned native path).
     */
    fun importHistory(points: List<GlucosePoint>): Boolean

    /**
     * Merge catch-up readings received from the phone↔watch peer after a reconnect (deduped by
     * timestamp). Returns the number of NEW readings added (0 = already up to date) so the UI can
     * report the outcome. Default no-op; the native path folds them into the displayed history so a
     * gap from while the peer was disconnected is filled.
     */
    fun mergeBackfill(points: List<Pair<Long, Double>>): Int = 0

    /**
     * Clear the active-sensor UI state (the sensor card + current reading) after the user deactivates
     * the sensor, so the app returns to the "no sensor / add sensor" state instead of still showing
     * it as active. Past history is kept. Default no-op.
     */
    fun clearActiveSensor() {}

    /**
     * After a scan/activation, reflect the now-registered sensor in [sensor] immediately — before the
     * BLE handshake completes and the first reading arrives — so the home screen shows a "connecting"
     * state instead of looking empty (no sensor / add-sensor) while the sensor is in fact coming up.
     * Default no-op.
     */
    fun showActivatedSensor() {}
}
