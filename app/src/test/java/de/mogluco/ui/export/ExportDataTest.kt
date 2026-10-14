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
package de.mogluco.ui.export

import de.mogluco.data.GlucosePoint
import de.mogluco.data.LoggedEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An export must never write "nothing, because it was not loaded yet" — and must still write an
 * empty file for a store that really holds nothing. [loadedLists] tells the two apart by the
 * repository's own word (GlucoseRepository.historyLoaded).
 */
class ExportDataTest {

    private val stored = listOf(GlucosePoint(60_000L, 101.0), GlucosePoint(120_000L, 104.0))
    private val logged = listOf(LoggedEvent(epochMillis = 90_000L, type = 3, value = 12f))

    private val history = MutableStateFlow<List<GlucosePoint>>(emptyList())
    private val loaded = MutableStateFlow(false)
    private val events = MutableStateFlow(logged)

    @Test
    fun aLoadedHistoryIsRead() = runBlocking<Unit> {
        history.value = stored
        loaded.value = true
        assertEquals(stored to logged, loadedLists(history, loaded, events))
    }

    @Test
    fun aLoadedStoreWithoutDataIsExportedEmpty() = runBlocking<Unit> {
        events.value = emptyList()
        loaded.value = true
        val lists = loadedLists(history, loaded, events)
        assertTrue(lists != null && lists.first.isEmpty() && lists.second.isEmpty())
    }

    @Test
    fun aHistoryStillLoadingIsNotExported() = runBlocking<Unit> {
        // A live reading that landed before the stored ones: not a loaded history.
        history.value = stored.takeLast(1)
        assertNull(loadedLists(history, loaded, events, timeoutMillis = 50))
    }

    @Test
    fun theExportWaitsForAHistoryThatIsAboutToLoad() = runBlocking<Unit> {
        launch {
            delay(50)
            history.value = stored
            loaded.value = true
        }
        assertEquals(stored to logged, loadedLists(history, loaded, events, timeoutMillis = 10_000))
    }

    @Test
    fun aRepositoryThatHasItsDataFromTheStartIsNotWaitedFor() = runBlocking<Unit> {
        // The interface's default: loaded, as a plain flow that completes.
        history.value = stored
        assertEquals(stored to logged, loadedLists(history, flowOf(true), events, timeoutMillis = 50))
        // And one that ends without ever being loaded must not throw.
        assertNull(loadedLists(history, flowOf(false), events))
    }
}
