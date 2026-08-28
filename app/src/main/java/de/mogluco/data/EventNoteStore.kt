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

import android.content.Context
import java.io.File
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * Persistent side-store for event notes.
 *
 * The native engine has no note channel at all: `Num` (Common/src/main/cpp/nums/num.h) is a fixed
 * 16-byte record — time, a meal/libre union, value, type — with nowhere to put text, and
 * `Natives.saveNum` takes no string. Notes were accepted by the entry sheet and then dropped on
 * save, so reopening an event always showed an empty field. Rather than change an on-disk layout
 * that the engine, the LibreView/Nightscout uploads and the watch all read, notes are kept beside
 * it here.
 *
 * Events carry no id natively, so a note is keyed by the same (time, type, value) tuple the engine
 * itself uses to identify a row for deletion and duplicate detection (see [EventRepository.update]
 * and [EventRepository.importEvents]). One consequence is worth knowing: two events sharing a
 * timestamp, type and value are a single event as far as the engine is concerned — it collapses
 * them on import — so they also share a note. That follows the engine's model rather than
 * inventing an identity it has no way to store.
 *
 * Lives in `app_eventnotes/` rather than the engine's `files/` directory, so a file the native
 * code knows nothing about can never show up in one of its own directory scans.
 */
class EventNoteStore(context: Context) {

    private val file = File(context.getDir("eventnotes", Context.MODE_PRIVATE), "notes.json")

    // The repository mutates from Dispatchers.Default while the UI thread reads back through
    // refresh(), so every access to [notes] and the file is serialised on this lock.
    private val lock = Any()
    private val notes: MutableMap<String, String> = loadFromDisk()

    /** Identity of an event, matching the engine's own (time, type, value) match in Engine. */
    private fun key(timeSec: Long, type: Int, value: Float) =
        "$timeSec|$type|${(value * 1000f).roundToInt()}"

    private fun loadFromDisk(): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        if (!file.exists()) return out
        // A truncated or corrupt file must not take the app down on launch: the events themselves
        // live in the engine and still load, so degrade to "no notes" rather than crashing.
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            root.keys().forEach { k -> root.optString(k, "").takeIf { it.isNotEmpty() }?.let { out[k] = it } }
        }
        return out
    }

    /** Write via a temp file + rename, so a crash mid-save can't leave a half-written store. */
    private fun persistLocked() {
        val text = JSONObject().apply { notes.forEach { (k, v) -> put(k, v) } }.toString()
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // Rename can fail on some vendor filesystems; a direct write still beats losing
                // the note, it just isn't atomic.
                file.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    /** The note attached to an event, or "" when it has none. */
    fun note(timeSec: Long, type: Int, value: Float): String = synchronized(lock) {
        notes[key(timeSec, type, value)].orEmpty()
    }

    /** Attach [note] to an event; a blank note drops the entry rather than storing "". */
    fun put(timeSec: Long, type: Int, value: Float, note: String): Unit = synchronized(lock) {
        val k = key(timeSec, type, value)
        val trimmed = note.trim()
        val changed = if (trimmed.isEmpty()) notes.remove(k) != null else notes.put(k, trimmed) != trimmed
        if (changed) persistLocked()
    }

    fun remove(timeSec: Long, type: Int, value: Float): Unit = synchronized(lock) {
        if (notes.remove(key(timeSec, type, value)) != null) persistLocked()
    }

    /**
     * Merge notes from a restored backup — one file write for the whole batch, which matters when
     * a restore carries thousands of events. Only adds: an imported event with no note never
     * clears a note already held here, mirroring importEvents' merge (existing kept, duplicates
     * skipped). Notes are restored for events that already exist locally too, so a backup can
     * repair a store whose notes were lost.
     */
    fun merge(events: Collection<LoggedEvent>): Unit = synchronized(lock) {
        var changed = false
        events.forEach { e ->
            val note = e.note.trim()
            if (note.isNotEmpty()) {
                val k = key(e.epochMillis / 1000L, e.type, e.value)
                if (!notes.containsKey(k)) {
                    notes[k] = note
                    changed = true
                }
            }
        }
        if (changed) persistLocked()
    }

    /** Drop notes for events before [cutoffSec], mirroring Engine.deleteEventsBefore. */
    fun removeBefore(cutoffSec: Long): Unit = synchronized(lock) {
        // A key whose time can't be parsed is left alone rather than assumed old — dropping a note
        // we can't place would be silent data loss.
        val stale = notes.keys.filter { (it.substringBefore('|').toLongOrNull() ?: Long.MAX_VALUE) < cutoffSec }
        if (stale.isNotEmpty()) {
            stale.forEach { notes.remove(it) }
            persistLocked()
        }
    }
}
