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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.NavigableSet
import java.util.TreeSet

/**
 * The manual "Push history": that a cancellation ends it ([attempt], [pushInChunks]) and that what
 * it says afterwards is what it did ([pushMessage]). The requests are stand-ins; Health Connect
 * itself is not in these tests.
 */
class HealthConnectPushTest {

    private val minute = 60_000L
    private val now = 1_786_000_000_000L

    /** One reading a minute, [requests] requests' worth. */
    private fun history(requests: Int) = List(requests * BATCH_SIZE) { GlucosePoint(now + it * minute, 100.0) }

    @Test
    fun aCancelledCallIsNotTakenForOneThatFailed() = runBlocking<Unit> {
        var wentOn = false
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            attempt { awaitCancellation() }
            wentOn = true
        }
        caller.cancelAndJoin()
        assertFalse(wentOn)
    }

    @Test
    fun aCallThatFailsIsAFailureAndNothingMore() = runBlocking<Unit> {
        assertTrue(attempt { error("Health Connect did not take it") }.isFailure)
        // Cancelled somewhere inside the call, not here: a failure too — the caller goes on.
        assertTrue(attempt { throw CancellationException("theirs") }.isFailure)
        assertEquals(3, attempt { 3 }.getOrNull())
    }

    @Test
    fun aPushCancelledDuringALookupWritesNothingMore() = runBlocking<Unit> {
        val lookups = ArrayList<Int>()
        val written = ArrayList<GlucosePoint>()
        val push = launch(start = CoroutineStart.UNDISPATCHED) {
            pushInChunks(history(requests = 3)) { chunk ->
                // A request as [HealthConnectSync] makes it: the lookup, here still under way
                // when the push is cancelled, then the write of what it did not find.
                lookups.add(chunk.size)
                val records: NavigableSet<Long>? = attempt<NavigableSet<Long>> { awaitCancellation() }.getOrNull()
                val missing = notInHealthConnect(chunk, records)
                written.addAll(missing)
                PushResult(written = missing.size, alreadyThere = chunk.size - missing.size)
            }
        }
        push.cancelAndJoin()
        // Not "not readable, so write it all", and not the requests after it either.
        assertEquals(emptyList<GlucosePoint>(), written)
        assertEquals(listOf(BATCH_SIZE), lookups)
    }

    @Test
    fun aPushCancelledBetweenRequestsSendsNoMore() = runBlocking<Unit> {
        var requests = 0
        val push = launch(start = CoroutineStart.UNDISPATCHED) {
            pushInChunks(history(requests = 3)) { chunk ->
                requests++
                currentCoroutineContext().cancel() // nothing in this request notices
                PushResult(written = chunk.size)
            }
        }
        push.join()
        assertTrue(push.isCancelled)
        assertEquals(1, requests)
    }

    @Test
    fun aPushAddsUpWhatBecameOfEveryReading() = runBlocking<Unit> {
        val points = history(requests = 3).dropLast(100)
        // The first request finds half of it there, the second is not taken, the third is new.
        val records = TreeSet(points.take(BATCH_SIZE / 2).map { it.epochMillis + 1_000 })
        var request = 0
        val result = pushInChunks(points) { chunk ->
            val missing = notInHealthConnect(chunk, records)
            when (request++) {
                1 -> PushResult(alreadyThere = chunk.size - missing.size, failed = missing.size)
                else -> PushResult(written = missing.size, alreadyThere = chunk.size - missing.size)
            }
        }
        assertEquals(3, request) // a request that fails does not end the push
        assertEquals(PushResult(written = BATCH_SIZE / 2 + BATCH_SIZE - 100, alreadyThere = BATCH_SIZE / 2, failed = BATCH_SIZE), result)
        assertEquals(points.size, result.written + result.alreadyThere + result.failed)
    }

    @Test
    fun nothingToPushIsNoRequest() = runBlocking<Unit> {
        assertEquals(PushResult(), pushInChunks(emptyList()) { error("no request for nothing") })
        assertEquals("No stored readings to push", pushMessage(PushResult()))
    }

    @Test
    fun theMessageSaysWhatWasWritten() {
        assertEquals("Grant Health Connect access first", pushMessage(null))
        assertEquals("Wrote 1200 readings to Health Connect", pushMessage(PushResult(written = 1200)))
        assertEquals("Wrote 1 reading to Health Connect", pushMessage(PushResult(written = 1)))
        // Pushed again: what is there is not counted as pushed.
        assertEquals("Nothing new: 1200 readings already in Health Connect", pushMessage(PushResult(alreadyThere = 1200)))
        assertEquals(
            "Wrote 40 readings to Health Connect, 1160 were already there",
            pushMessage(PushResult(written = 40, alreadyThere = 1160)),
        )
        assertEquals(
            "Wrote 40 readings to Health Connect, 1 was already there",
            pushMessage(PushResult(written = 40, alreadyThere = 1)),
        )
    }

    @Test
    fun theMessageSaysWhatWasNotWritten() {
        assertEquals(
            "Wrote 900 readings to Health Connect. 300 readings could not be written. Push again to retry",
            pushMessage(PushResult(written = 900, failed = 300)),
        )
        assertEquals(
            "Wrote nothing to Health Connect. 1200 readings could not be written. Push again to retry",
            pushMessage(PushResult(failed = 1200)),
        )
        assertEquals(
            "Wrote nothing to Health Connect, 900 were already there. 1 reading could not be written. Push again to retry",
            pushMessage(PushResult(alreadyThere = 900, failed = 1)),
        )
    }
}
