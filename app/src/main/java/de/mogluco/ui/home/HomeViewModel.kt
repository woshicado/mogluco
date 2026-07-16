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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import de.mogluco.data.AppPreferences
import de.mogluco.data.EventCategory
import de.mogluco.data.LogKind
import de.mogluco.data.EventRepository
import de.mogluco.data.EventType
import de.mogluco.data.GlucosePoint
import de.mogluco.data.GlucoseReading
import de.mogluco.data.GlucoseRepository
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.LoggedEvent
import de.mogluco.data.ReadingDisplay
import de.mogluco.data.SensorInfo
import de.mogluco.data.curatedDisplayTypes

data class HomeUiState(
    val reading: GlucoseReading? = null,
    val unit: GlucoseUnit = GlucoseUnit.MGDL,
    val targetLow: Double = 70.0,
    val targetHigh: Double = 180.0,
    val history: List<GlucosePoint> = emptyList(),
    val sensor: SensorInfo? = null,
    val readingDisplay: ReadingDisplay = ReadingDisplay.BOTH,
    val showTargetBand: Boolean = true,
    val enabledLogKinds: Set<LogKind> = LogKind.entries.toSet(),
    val enabledCategories: Set<EventCategory> = EventCategory.entries.toSet(),
    val gapThresholdMillis: Long = 20 * 60_000L,
    val events: List<LoggedEvent> = emptyList(),
)

class HomeViewModel(
    repository: GlucoseRepository,
    private val eventRepository: EventRepository,
    prefs: AppPreferences,
) : ViewModel() {

    /**
     * Curated, ordered event vocabulary for the add/edit UI (Blood glucose, Activity, Slow/Fast
     * insulin, Carbohydrates, Dextrose), mapped onto the engine's native indices. Activity is
     * dropped when the user disabled it. Computed on demand so the caller can key it on the
     * current [showActivity].
     */
    fun displayTypes(enabled: Set<LogKind>): List<EventType> =
        curatedDisplayTypes(eventRepository.types, eventRepository.bloodIndex, enabled)

    private val core = combine(
        repository.latest,
        repository.unit,
        repository.targetRange,
        repository.history,
        repository.sensor,
    ) { reading, unit, range, history, sensor ->
        HomeUiState(
            reading = reading,
            unit = unit,
            targetLow = range.start,
            targetHigh = range.endInclusive,
            history = history,
            sensor = sensor,
        )
    }

    private data class DisplayState(
        val display: ReadingDisplay,
        val band: Boolean,
        val enabledLogKinds: Set<LogKind>,
        val enabledCategories: Set<EventCategory>,
        val gapMillis: Long,
        val events: List<LoggedEvent>,
    )

    private val display = combine(
        repository.readingDisplay,
        prefs.showTargetBand,
        prefs.enabledLogKinds,
        prefs.gapThresholdMinutes,
        eventRepository.recent,
    ) { display, band, kinds, gapMin, events ->
        // The FAB gates on the fine kinds; the graph/legend/calendar filter by category (a category
        // shows if any of its kinds is enabled). A disabled category vanishes at the source.
        val categories = kinds.map { it.category }.toSet()
        val filtered = events.filter { it.category in categories }
        DisplayState(display, band, kinds, categories, gapMin * 60_000L, filtered)
    }

    val uiState: StateFlow<HomeUiState> =
        combine(core, display) { state, d ->
            state.copy(
                readingDisplay = d.display,
                showTargetBand = d.band,
                enabledLogKinds = d.enabledLogKinds,
                enabledCategories = d.enabledCategories,
                gapThresholdMillis = d.gapMillis,
                events = d.events,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState(),
        )

    // Event mutations are synchronous JNI calls followed by a full event-log refresh — kept off
    // the main thread so saving from the sheet can't jank the frame that dismisses it.
    fun logEvent(type: EventType, value: Float, atMillis: Long, note: String) {
        viewModelScope.launch(Dispatchers.Default) { eventRepository.log(type, value, atMillis, note) }
    }

    fun updateEvent(original: LoggedEvent, edited: LoggedEvent) {
        viewModelScope.launch(Dispatchers.Default) { eventRepository.update(original, edited) }
    }

    fun deleteEvent(event: LoggedEvent) {
        viewModelScope.launch(Dispatchers.Default) { eventRepository.delete(event) }
    }
}
