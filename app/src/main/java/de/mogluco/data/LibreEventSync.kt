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

import android.util.Log
import tk.glucodata.Engine

/**
 * Enables logged insulin/carb events in LibreView uploads.
 *
 * The native upload path already knows how to embed insulin, food and note entries in the same
 * POST as the glucose readings, but it needs (a) each label classified in its librenums table and
 * (b) its global sendnumbers switch on — and this app is the only thing that can set either.
 * [sync] derives the classification from the same locale-proof taxonomy the add-event menu uses
 * ([EventType.classify]) and flips the switch. Run at every engine start so a renamed label is
 * re-classified; both stores are persistent (mmap) and the writes are idempotent.
 *
 * No data leaves the device from here: events ride inside the regular LibreView upload, which
 * only runs while "Upload to LibreView automatically" (or a one-shot push) is active.
 */
object LibreEventSync {
    private const val LOG_ID = "mogluco.LibreEventSync"

    // Native librenums kinds — Common/src/main/cpp/net/libreview/numcategories.hpp.
    private const val KIND_OFF = 0
    private const val KIND_RAPID_INSULIN = 1
    private const val KIND_LONG_INSULIN = 2
    private const val KIND_CARBS = 3

    /** Classify every engine label for LibreView and enable event upload. */
    fun sync() {
        if (!Engine.isStarted()) return
        val carbsIndex = runCatching { Engine.carbsType() }.getOrDefault(-1)
        val labels = Engine.getLabels()
        labels.forEachIndexed { index, name ->
            // Insulin splits by the slow-vs-fast helper the display names use; carbs and dextrose
            // are both logged in grams, so their grams-per-unit weight is 1. Activity, blood and
            // unrecognised labels stay local (kind 0) — LibreView has no proper channel for them.
            val kind = when (EventType.classify(index, name, carbsIndex)) {
                EventCategory.INSULIN ->
                    if (isSlowInsulinLabel(name)) KIND_LONG_INSULIN else KIND_RAPID_INSULIN
                EventCategory.CARBS -> KIND_CARBS
                else -> KIND_OFF
            }
            Engine.classifyEventLabel(index, kind, if (kind == KIND_CARBS) 1.0f else 0.0f)
        }
        Engine.setEventUpload(true)
        Log.i(LOG_ID, "classified ${labels.size} labels; event upload on")
    }
}
