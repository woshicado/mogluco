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
package de.mogluco.health

import de.mogluco.data.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TreeSet

/** Which readings the automatic Health Connect export writes, and when ([ExportProgress]). */
class HealthConnectExportTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    /** A whole second, like the times of the engine's store. */
    private val now = 1_786_000_000_000L

    private fun points(times: Iterable<Long>) = times.map { GlucosePoint(it, 100.0) }
    private fun points(vararg times: Long) = points(times.asIterable())
    private fun List<GlucosePoint>.times() = map { it.epochMillis }

    /** One reading a minute from [from] to [to], both included. */
    private fun everyMinute(from: Long, to: Long) = points(from..to step minute)

    /** Writes everything that is pending, as a pass of the export does when Health Connect takes it. */
    private fun ExportProgress.exportAll(history: List<GlucosePoint>, now: Long): List<Long> =
        pending(history, now).also { if (it.isNotEmpty()) written(it) }.times()

    @Test
    fun aReadingKeepsItsRecordIdWhenTheStoreRetimesIt() {
        assertEquals("1786000000000", recordId(1_786_000_000_000L)) // the store's time: as before
        assertEquals("1786000000000", recordId(1_786_000_000_123L)) // the live time of the same reading
        assertEquals("1786000060000", recordId(1_786_000_060_999L))
    }

    @Test
    fun aReadingStoredInTheNextSecondIsNotWrittenBesideItsLiveRecord() {
        // Received just before the second ended and exported live; the store saved it in the next.
        val live = now + 59_999
        val stored = now + minute
        // The id alone does not hold the two together …
        assertTrue(recordId(live) != recordId(stored))
        // … so what is pushed again is checked against the records this app has in Health Connect.
        val history = points(now, stored, now + 2 * minute)
        val records = TreeSet(listOf(now, live))
        assertEquals(listOf(now + 2 * minute), notInHealthConnect(history, records).times())
        // Pushed once more, everything is there under the time it was written with.
        assertEquals(emptyList<Long>(), notInHealthConnect(history, TreeSet(listOf(now, live, now + 2 * minute))).times())
    }

    @Test
    fun aRecordWrittenOnTheFullMillisecondIsNotWrittenAgain() {
        // An older version's live record: id and time on the millisecond, the store a second apart.
        val history = points(now, now + minute)
        assertEquals(emptyList<Long>(), notInHealthConnect(history, TreeSet(listOf(now + 123, now + minute - 877))).times())
    }

    @Test
    fun withoutTheRecordsEverythingIsWrittenUnderItsOwnId() {
        // Health Connect could not be asked: a point of the store still replaces itself by its id.
        val history = everyMinute(now, now + 5 * minute)
        assertEquals(history.times(), notInHealthConnect(history, null).times())
        // Nothing of this app there yet: all of it.
        assertEquals(history.times(), notInHealthConnect(history, TreeSet()).times())
    }

    @Test
    fun aPointWithinAReadingOfAnExportedOneIsNotExportedAgain() {
        val exported = TreeSet(listOf(now))
        val history = points(now - minute, now - 400, now + 900, now + minute)
        assertEquals(listOf(now - minute, now + minute), notExported(history, exported).times())
    }

    @Test
    fun aReadingHeldTwiceIsExportedOnce() {
        // The newest reading as the store has it and, still, as it came in live.
        val history = points(now - minute, now, now + 123)
        assertEquals(listOf(now - minute, now), notExported(history, TreeSet()).times())
    }

    @Test
    fun readingsNewerThanTheMarkAreWrittenOnce() {
        val export = ExportProgress(mark = now - 10 * minute)
        val history = everyMinute(now - hour, now)

        assertEquals((now - 9 * minute..now step minute).toList(), export.exportAll(history, now))
        assertEquals(now + SAME_READING_MS, export.mark)
        assertEquals(emptyList<Long>(), export.exportAll(history, now))

        val next = history + points(now + minute)
        assertEquals(listOf(now + minute), export.exportAll(next, now + minute))
    }

    @Test
    fun switchedOnNowNothingOfThePastIsExported() {
        val export = ExportProgress(mark = now)
        val history = everyMinute(now - 3 * hour, now)

        assertEquals(emptyList<Long>(), export.exportAll(history, now))
        assertEquals(now, export.mark)
        assertEquals(listOf(now + minute), export.exportAll(history + points(now + minute), now + minute))
    }

    @Test
    fun aGapFilledLaterIsExported() {
        // Out of range from two hours to one hour ago; readings since then are exported.
        val before = everyMinute(now - 5 * hour, now - 2 * hour)
        val after = everyMinute(now - hour, now)
        val export = ExportProgress(mark = now - 30 * minute)
        export.exportAll(before + after, now)

        // The sensor's backfill: five-minute points in the gap, all older than the mark.
        val filled = points(now - 2 * hour + 5 * minute..now - hour - 5 * minute step 5 * minute)
        val history = before + filled + after
        assertEquals(filled.times(), export.exportAll(history, now))
        assertEquals(emptyList<Long>(), export.exportAll(history, now))
        assertEquals(now + SAME_READING_MS, export.mark)
    }

    @Test
    fun aGapOlderThanTheWindowIsNotLookedFor() {
        val recent = everyMinute(now - hour, now)
        val export = ExportProgress(mark = now)
        export.exportAll(recent, now)

        val old = points(now - RECONCILE_WINDOW_MS - hour)
        assertEquals(emptyList<Long>(), export.exportAll(old + recent, now))
    }

    @Test
    fun theStoreRetimingAReadingDoesNotExportItAgain() {
        val export = ExportProgress(mark = now - minute)
        val stored = everyMinute(now - hour, now - minute)
        // Live: stamped with the millisecond it came in at.
        assertEquals(listOf(now + 123), export.exportAll(stored + points(now + 123), now + 123))
        // Reloaded: the store has it at the second it saved it in — the one before, or the next.
        assertEquals(emptyList<Long>(), export.exportAll(stored + points(now), now + minute))
        assertEquals(emptyList<Long>(), export.exportAll(stored + points(now + 1_000), now + minute))
        assertEquals(emptyList<Long>(), export.exportAll(stored + points(now, now + 123), now + minute))
    }

    @Test
    fun aRestartTakesTheHistoryUpToTheMarkAsExported() {
        val first = ExportProgress(mark = now - hour)
        val history = everyMinute(now - 3 * hour, now - minute) + points(now + 123)
        first.exportAll(history, now + 123)

        // The next process finds the last reading at the store's time, on either side of the live one.
        for (retimed in listOf(now, now + 1_000)) {
            val second = ExportProgress(first.mark)
            val reloaded = everyMinute(now - 3 * hour, now - minute) + points(retimed)
            assertEquals(emptyList<Long>(), second.exportAll(reloaded, now + minute))
        }
    }

    @Test
    fun nothingIsTakenAsGivenBeforeTheStoredHistoryIsThere() {
        // The last process got as far as an hour ago; the store holds readings up to 30 minutes ago.
        val export = ExportProgress(mark = now - hour)
        assertEquals(emptyList<Long>(), export.exportAll(emptyList(), now))

        // A live reading lands before the stored history is loaded.
        assertEquals(listOf(now + 123), export.exportAll(points(now + 123), now + 123))

        // Then the store: only what the last process had not written is new.
        val stored = everyMinute(now - 3 * hour, now - 30 * minute)
        assertEquals(
            (now - hour + minute..now - 30 * minute step minute).toList(),
            export.exportAll(stored + points(now), now + minute),
        )
    }

    @Test
    fun whatWasNotWrittenIsTriedAgainAndTheMarkStaysBehindIt() {
        val export = ExportProgress(mark = now - 10 * minute)
        val history = everyMinute(now - hour, now)

        val pending = export.pending(history, now)
        export.written(pending.take(4))
        export.notWritten(pending[4])

        assertTrue(export.mark < pending[4].epochMillis)
        assertEquals(pending.drop(4).times(), export.pending(history, now).times())
        // And the same for the process after this one.
        assertEquals(pending.drop(4).times(), ExportProgress(export.mark).pending(history, now).times())
    }

    @Test
    fun aFilledGapThatWasNotWrittenSurvivesARestart() {
        val before = everyMinute(now - 5 * hour, now - 2 * hour)
        val after = everyMinute(now - hour, now)
        val export = ExportProgress(mark = now - 30 * minute)
        export.exportAll(before + after, now)
        val markBefore = export.mark

        val filled = points(now - 2 * hour + 5 * minute..now - hour - 5 * minute step 5 * minute)
        val history = before + filled + after
        export.notWritten(export.pending(history, now).first()) // no permission, or unavailable

        assertTrue(export.mark < filled.first().epochMillis)
        assertTrue(export.mark < markBefore)
        // Still pending here, and only the gap: the rest is known to be written.
        assertEquals(filled.times(), export.pending(history, now).times())
        // The next process does not know what was written after the gap and has that pending
        // again. It is older than that process, so it is looked up in Health Connect first: only
        // the gap is written, also where the store has given a reading its next second since.
        val restarted = ExportProgress(export.mark).pending(history, now).times()
        assertEquals(filled.times() + after.times(), restarted)
        assertEquals(restarted.size, restarted.map(::recordId).toSet().size)
        val records = TreeSet(after.times().map { it - 1 })
        assertEquals(filled.times(), notInHealthConnect(points(restarted), records).times())
    }

    @Test
    fun readingsThatWaitedLongerThanTheWindowAreStillWritten() {
        // No permission for two days: everything since the mark is pending, not only the last day.
        val mark = now - 48 * hour
        val history = points(now - 50 * hour..now step 5 * minute)
        val export = ExportProgress(mark)

        val pending = export.pending(history, now)
        assertEquals(mark + 5 * minute, pending.first().epochMillis)
        assertEquals(now, pending.last().epochMillis)

        export.notWritten(pending.first())
        assertEquals(mark, export.mark)
        assertEquals(pending.times(), export.exportAll(history, now + minute))
        assertEquals(emptyList<Long>(), export.exportAll(history, now + minute))
    }

    @Test
    fun aLiveReadingAheadOfTheStoredHistoryDoesNotHideWhatWaited() {
        // As above, no permission for two days — and then the process starts, and a live reading
        // lands before the stored history is loaded.
        val mark = now - 48 * hour
        val stored = points(now - 50 * hour..now - 5 * minute step 5 * minute)
        val waited = (mark + 5 * minute..now - 5 * minute step 5 * minute).toList()
        val export = ExportProgress(mark)

        assertEquals(listOf(now), export.exportAll(points(now), now))
        // Written, but the mark stays: nothing says yet what the store holds before that reading.
        assertEquals(mark, export.mark)

        // Then the store, the live reading at the second it saved it in: everything since the
        // mark, also what is older than the window, and the live reading not again.
        val history = stored + points(now + 1_000)
        assertEquals(waited, export.exportAll(history, now + minute))
        assertEquals(now + SAME_READING_MS, export.mark)
        assertEquals(emptyList<Long>(), export.exportAll(history, now + minute))

        // The store first and the live reading after it comes to the same.
        val storeFirst = ExportProgress(mark)
        assertEquals(waited, storeFirst.exportAll(stored, now))
        assertEquals(listOf(now), storeFirst.exportAll(stored + points(now), now))
        assertEquals(export.mark, storeFirst.mark)

        // Had the process ended before the store was loaded, the next one starts from the same
        // mark: all of it is pending there, the live reading too — which it looks up first.
        val restarted = ExportProgress(mark).pending(history, now + minute).times()
        assertEquals(waited + (now + 1_000), restarted)
        assertEquals(waited, notInHealthConnect(points(restarted), TreeSet(listOf(now))).times())
    }

    @Test
    fun aLiveReadingAheadOfTheStoredHistoryThatCouldNotBeWrittenLeavesTheMarkBehindIt() {
        val mark = now - 48 * hour
        val export = ExportProgress(mark)
        export.exportAll(points(now), now)

        val history = points(now - 50 * hour..now - 5 * minute step 5 * minute) + points(now + 1_000)
        val pending = export.pending(history, now + minute)
        export.written(pending.take(4))
        // Not the last of it: the mark does not pass what is still to be written.
        assertEquals(pending[3].epochMillis + SAME_READING_MS, export.mark)
        export.notWritten(pending[4])

        assertEquals(pending.drop(4).times(), export.exportAll(history, now + 2 * minute))
        assertEquals(now + SAME_READING_MS, export.mark)
    }

    @Test
    fun aHistoryThatBeginsAfterTheMarkDoesNotHoldTheMarkForGood() {
        // Switched on two days ago with no reading stored yet, or the readings from then are
        // gone: the store begins after the mark and never reaches back to it.
        val mark = now - 48 * hour
        val export = ExportProgress(mark)
        val stored = everyMinute(now - hour, now)

        // It could still be the live readings ahead of the store: written, the mark waits.
        assertEquals(stored.times(), export.exportAll(stored, now))
        assertEquals(mark, export.mark)
        assertEquals(listOf(now + minute), export.exportAll(stored + points(now + minute), now + minute))
        assertEquals(mark, export.mark)

        // The store has had its time: this is the history, and the mark follows it.
        val later = everyMinute(now - hour, now + HISTORY_LOAD_MS)
        assertEquals(
            (now + 2 * minute..now + HISTORY_LOAD_MS step minute).toList(),
            export.exportAll(later, now + HISTORY_LOAD_MS),
        )
        assertEquals(now + HISTORY_LOAD_MS + SAME_READING_MS, export.mark)
        assertEquals(emptyList<Long>(), export.exportAll(later, now + HISTORY_LOAD_MS))

        // Also where nothing new came with it: what was written before is all there is.
        val quiet = ExportProgress(mark)
        quiet.exportAll(stored, now)
        assertEquals(emptyList<Long>(), quiet.exportAll(stored, now + HISTORY_LOAD_MS))
        assertEquals(now + SAME_READING_MS, quiet.mark)
        // And the process after that one finds its mark inside the history, as any other does.
        assertEquals(emptyList<Long>(), ExportProgress(quiet.mark).exportAll(stored, now + HISTORY_LOAD_MS))
    }
}
