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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [EventRepository] seeded from [SampleData] for building/verifying the UI. */
class MockEventRepository : EventRepository {

    override val types: List<EventType> = SampleData.eventTypes

    override val bloodIndex: Int = -1

    private val _recent = MutableStateFlow(SampleData.events())
    override val recent: Flow<List<LoggedEvent>> = _recent.asStateFlow()

    override fun log(type: EventType, value: Float, atMillis: Long, note: String) {
        val e = LoggedEvent(
            epochMillis = atMillis,
            type = type.index,
            value = value,
            note = note,
            category = type.category,
            label = type.name,
        )
        _recent.value = (_recent.value + e).sortedBy { it.epochMillis }
    }

    override fun update(original: LoggedEvent, edited: LoggedEvent) {
        _recent.value = _recent.value.map { if (it.id == original.id) edited else it }
            .sortedBy { it.epochMillis }
    }

    override fun delete(event: LoggedEvent) {
        _recent.value = _recent.value.filterNot { it.id == event.id }
    }

    override fun importEvents(events: List<LoggedEvent>) {
        // Merge: keep existing, add only events not already present (matched by id).
        val have = _recent.value.associateBy { it.id }.toMutableMap()
        events.forEach { have.putIfAbsent(it.id, it) }
        _recent.value = have.values.sortedBy { it.epochMillis }
    }

    override fun deleteBefore(millis: Long) {
        _recent.value = _recent.value.filter { it.epochMillis >= millis }
    }
}
