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
package de.mogluco.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import de.mogluco.data.AppPreferences
import de.mogluco.data.EventRepository
import de.mogluco.data.GlucoseRepository
import de.mogluco.data.SensorInfo
import de.mogluco.ui.export.ExportData
import de.mogluco.ui.export.loadExportData

/**
 * Backs the Settings tab and its subscreens. Display-level settings live in
 * [AppPreferences] (the redesign owns them); the repositories provide the sensor and the
 * data snapshots the export modal needs.
 */
class SettingsViewModel(
    val prefs: AppPreferences,
    private val glucoseRepository: GlucoseRepository,
    private val eventRepository: EventRepository,
) : ViewModel() {

    /**
     * Restore settings + events + glucose history from a parsed backup.
     *
     * @return whether the glucose points made it in. On native builds the engine owns glucose
     * storage and [GlucoseRepository.importHistory] refuses, so settings/events restore but
     * readings don't — the caller words its toast accordingly.
     */
    fun applyRestore(backup: de.mogluco.ui.export.Backup): Boolean {
        if (backup.settings.isNotEmpty()) {
            prefs.importAll(backup.settings)
        }
        if (backup.events.isNotEmpty()) eventRepository.importEvents(backup.events)
        return backup.glucose.isEmpty() || glucoseRepository.importHistory(backup.glucose)
    }

    /**
     * Delete events — and readings, where the store allows it — before [millis].
     *
     * @return whether glucose readings were actually deleted; false on native builds, where the
     * engine owns the history and only events are removed.
     */
    fun deleteHistoryBefore(millis: Long): Boolean {
        val glucoseDeleted = glucoseRepository.deleteBefore(millis)
        eventRepository.deleteBefore(millis)
        return glucoseDeleted
    }

    /**
     * Deactivate the active sensor: finish it in the engine, tear down its BLE, and clear the
     * active-sensor UI state so the app returns to "no sensor / add sensor". Returns false (and does
     * nothing) when the live engine isn't running (sample-data mode).
     */
    fun deactivateSensor(serial: String): Boolean {
        if (!tk.glucodata.Engine.isStarted()) return false
        tk.glucodata.Sensors.deactivate(serial)
        glucoseRepository.clearActiveSensor()
        return true
    }

    val sensor: StateFlow<SensorInfo?> =
        glucoseRepository.sensor.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The readings and events for the export modal, read from the repositories when the file is
     * written; null while the stored history is still loading (see [loadExportData]).
     */
    suspend fun exportData(): ExportData? = loadExportData(glucoseRepository, eventRepository)
}
