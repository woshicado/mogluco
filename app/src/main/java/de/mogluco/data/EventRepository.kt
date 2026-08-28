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
import kotlin.math.roundToInt
import tk.glucodata.Engine

/** Reads the event taxonomy and logs/reads carb/insulin/note events via the native engine. */
interface EventRepository {
    /** Available event types from the engine, classified. */
    val types: List<EventType>

    /** The native label index for a fingerstick blood-glucose reference, or -1 if unknown. */
    val bloodIndex: Int

    /** All logged events (newest last). */
    val recent: Flow<List<LoggedEvent>>

    /** Log an event at the given time (defaults to now), with an optional note. */
    fun log(type: EventType, value: Float, atMillis: Long = System.currentTimeMillis(), note: String = "")

    // The mutating operations below are abstract on purpose: every implementation must handle
    // them, so the native path can't silently inherit a no-op and make the UI report an edit /
    // delete / restore that never reached the engine.

    /**
     * Replace an existing event. [original] is the event as it was stored (the delete key);
     * [edited] is the new content. Passing both is required because the engine matches events by
     * their (time, type, value) tuple — editing any of those would otherwise fail to delete the
     * old row and silently create a duplicate.
     */
    fun update(original: LoggedEvent, edited: LoggedEvent)

    /** Delete an event. */
    fun delete(event: LoggedEvent)

    /** Merge events into the log (used when restoring a backup): existing kept, duplicates skipped. */
    fun importEvents(events: List<LoggedEvent>)

    /** Delete all events strictly before [millis]. */
    fun deleteBefore(millis: Long)
}

/**
 * @param notes side-store for event text. The engine has no note channel (see [EventNoteStore]),
 *   so notes are written next to it under the same (time, type, value) key the engine uses to
 *   identify a row — which is why every mutation below keeps the two in step.
 */
class NativeEventRepository(private val notes: EventNoteStore) : EventRepository {

    private val _recent = MutableStateFlow<List<LoggedEvent>>(emptyList())
    override val recent: Flow<List<LoggedEvent>> = _recent.asStateFlow()

    override val types: List<EventType> by lazy {
        val carbsIndex = runCatching { Engine.carbsType() }.getOrDefault(-1)
        Engine.getLabels()
            .mapIndexed { index, name ->
                EventType(index, name.trim(), EventType.classify(index, name, carbsIndex))
            }
            .filter { it.name.isNotEmpty() }
    }

    override val bloodIndex: Int by lazy { runCatching { Engine.bloodType() }.getOrDefault(-1) }

    init {
        refresh()
    }

    override fun log(type: EventType, value: Float, atMillis: Long, note: String) {
        Engine.logEvent(type.index, value, atMillis)
        // Same second-resolution truncation the engine applies (saveNum takes timeMillis / 1000),
        // so the key written here is the one refresh() reads back.
        notes.put(atMillis / 1000L, type.index, value, note)
        refresh()
    }

    // Events carry no stable id natively, so update = delete-the-old + log-the-new. The OLD tuple
    // (original) is the delete key — using the edited tuple would never match the stored row and
    // would just append a duplicate.
    override fun update(original: LoggedEvent, edited: LoggedEvent) {
        Engine.deleteEvent(original.epochMillis, original.type, original.value)
        Engine.logEvent(edited.type, edited.value, edited.epochMillis)
        // The note is keyed by that same tuple, so editing the time, type or value moves it:
        // retire the old key first, then write the new one. When the tuple is unchanged the two
        // keys are equal and this collapses to a plain rewrite of the text.
        notes.remove(original.epochMillis / 1000L, original.type, original.value)
        notes.put(edited.epochMillis / 1000L, edited.type, edited.value, edited.note)
        refresh()
    }

    override fun delete(event: LoggedEvent) {
        Engine.deleteEvent(event.epochMillis, event.type, event.value)
        notes.remove(event.epochMillis / 1000L, event.type, event.value)
        refresh()
    }

    // Restoring a backup *merges* into the existing log: keep current events, add only those not
    // already stored, and skip duplicates within the import. As a clean-up it also collapses any
    // pre-existing exact duplicates (same time+type+value) to a single copy, so re-importing fixes
    // a store that earlier appends had duplicated. Identity is the (timeSec, type, value≈) tuple.
    override fun importEvents(events: List<LoggedEvent>) {
        fun key(timeSec: Long, type: Int, value: Float) = Triple(timeSec, type, (value * 1000f).roundToInt())
        val seen = HashSet<Triple<Long, Int, Int>>()
        // Collapse duplicates already in the store (keep the first of each).
        Engine.recentEvents(Int.MAX_VALUE).forEach {
            if (!seen.add(key(it.time, it.type, it.value))) {
                Engine.deleteEvent(it.time * 1000L, it.type, it.value)
            }
        }
        // Merge in the imported events that aren't present yet.
        events.forEach { e ->
            if (seen.add(key(e.epochMillis / 1000L, e.type, e.value))) {
                Engine.logEvent(e.type, e.value, e.epochMillis)
            }
        }
        // Notes for the whole batch, not just the newly inserted rows: an event that already
        // existed here without a note should still get the one the backup carries.
        notes.merge(events)
        refresh()
    }

    override fun deleteBefore(millis: Long) {
        Engine.deleteEventsBefore(millis)
        notes.removeBefore(millis / 1000L)
        refresh()
    }

    private fun refresh() {
        val byIndex = types.associateBy { it.index }
        val bi = bloodIndex
        // Load the FULL store, not a recent window: [recent] feeds exports, backups, the calendar
        // dots and the day lists, all of which need every event — a window would silently truncate
        // them all. Single StateFlow assignment, so a concurrent refresh can't interleave.
        _recent.value = Engine.recentEvents(Int.MAX_VALUE).map {
            val raw = byIndex[it.type]
            val category = if (it.type == bi) EventCategory.BLOOD else raw?.category ?: EventCategory.OTHER
            LoggedEvent(
                epochMillis = it.time * 1000L,
                type = it.type,
                value = it.value,
                note = notes.note(it.time, it.type, it.value),
                category = category,
                label = eventDisplayName(raw?.name.orEmpty(), category),
            )
        }
    }
}
