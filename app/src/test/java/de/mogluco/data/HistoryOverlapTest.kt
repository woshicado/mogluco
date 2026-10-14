package de.mogluco.data

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.Engine

/**
 * One sensor at a time in the stored history: :core's [Engine.overlapSpans] and [Engine.mergeHistory],
 * the pure part of Engine.glucoseHistory() — a replaced sensor that keeps transmitting next to its
 * successor is left out for as long as the successor delivers.
 */
class HistoryOverlapTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val noon = 1_700_000_000_000L
    private val none = Long.MAX_VALUE
    private val tail = 15 * minute

    /** A stored sensor; its readings all carry [mgdl], so a point tells which sensor it is from. */
    private class Sensor(
        val start: Long,
        val mgdl: Long,
        val poll: List<LongArray> = emptyList(),
        val historic: List<LongArray> = emptyList(),
    )

    private fun sensor(
        start: Long,
        mgdl: Long,
        poll: Iterable<Long> = emptyList(),
        historic: Iterable<Long> = emptyList(),
    ) = Sensor(start, mgdl, poll.map { longArrayOf(it, mgdl) }, historic.map { longArrayOf(it, mgdl) })

    /** Every minute from [from] up to [until] (exclusive), [offset] past the minute. */
    private fun minutes(from: Long, until: Long, offset: Long = 0L) = (from + offset) until until step minute

    private fun fiveMinutes(from: Long, until: Long) = from until until step 5 * minute

    /** The history as (time, mg/dL) pairs. */
    private fun history(vararg sensors: Sensor): List<Pair<Long, Long>> =
        Engine.mergeHistory(
            LongArray(sensors.size) { sensors[it].start },
            sensors.map { it.poll },
            sensors.map { it.historic },
        ).toList().chunked(2).map { it[0] to it[1] }

    /** The history as it was before sensors were cut: all streams merged, whatever the sensor. */
    private fun asBefore(vararg sensors: Sensor): List<Pair<Long, Long>> {
        val poll = sensors.flatMap { it.poll }.sortedBy { it[0] }
        val filling = sensors.flatMap { it.historic }
            .filter { h -> poll.none { abs(it[0] - h[0]) <= 3 * minute } }
        return (poll + filling).sortedBy { it[0] }.map { it[0] to it[1] }
    }

    private fun List<Pair<Long, Long>>.of(sensor: Sensor) = filter { it.second == sensor.mgdl }.map { it.first }

    // --- nothing to cut: as before ---

    @Test
    fun aSingleSensorComesOutAsBefore() {
        // Live from 13:00, out of reach 15:00–16:00 (the historic stream fills that), live again.
        val a = sensor(
            start = noon,
            mgdl = 100,
            poll = minutes(noon + hour, noon + 3 * hour) + minutes(noon + 4 * hour, noon + 6 * hour),
            historic = fiveMinutes(noon + hour, noon + 6 * hour),
        )
        val history = history(a)
        assertEquals(asBefore(a), history)
        assertTrue(history.size > a.poll.size) // the hole is filled
        assertEquals(history.sortedBy { it.first }, history)
    }

    @Test
    fun sensorsWornOneAfterAnotherComeOutAsBefore() {
        // A delivers through B's warm-up and is taken off before B's first reading; C follows B
        // after a day without a sensor.
        val startB = noon + 10 * hour
        val startC = noon + 50 * hour
        val endA = startB + 50 * minute
        val endB = startB + 16 * hour
        val a = sensor(noon, 100, minutes(noon + hour, endA), fiveMinutes(noon + hour, endA))
        val b = sensor(startB, 200, minutes(startB + hour, endB, offset = 20_000L), fiveMinutes(startB + hour, endB))
        val c = sensor(startC, 300, minutes(startC + hour, startC + 5 * hour))
        val before = asBefore(a, b, c)
        assertEquals(before, history(a, b, c))
        assertEquals(before, history(c, a, b)) // whatever the order in the store
        assertEquals(a.poll.size + b.poll.size + c.poll.size, before.size)
    }

    @Test
    fun aNewSensorThatIsStillWarmingUpCutsNothing() {
        val a = sensor(noon, 100, minutes(noon + hour, noon + 20 * hour))
        val b = sensor(noon + 19 * hour, 200) // activated, no reading yet
        assertEquals(asBefore(a, b), history(a, b))
    }

    // --- the cut ---

    @Test
    fun aReplacedSensorThatKeepsTransmittingIsCutAtTheNewOnesFirstReading() {
        // B is put on at 22:00 without deactivating A; A transmits next to it for another day.
        val startB = noon + 10 * hour
        val end = startB + 24 * hour
        val a = sensor(noon, 100, minutes(noon + hour, end), fiveMinutes(noon + hour, end))
        val b = sensor(startB, 200, minutes(startB + hour, end, offset = 20_000L))
        for (history in listOf(history(a, b), history(b, a))) {
            // A up to B's first reading — through B's warm-up —, from there on B alone.
            assertEquals(a.poll.map { it[0] }.filter { it < startB + hour + 20_000L }, history.of(a))
            assertEquals(b.poll.map { it[0] }, history.of(b))
            assertEquals(200L, history.last().second)
            assertEquals(history.sortedBy { it.first }, history)
        }
    }

    @Test
    fun aReadingAtTheVeryTimeOfTheNewSensorsFirstOneIsLeftToTheNewSensor() {
        val firstB = noon + 11 * hour
        val a = sensor(noon, 100, minutes(noon + hour, noon + 12 * hour))
        val b = sensor(noon + 10 * hour, 200, minutes(firstB, noon + 12 * hour))
        val history = history(a, b)
        assertEquals(firstB - minute, history.of(a).last())
        assertEquals(firstB, history.of(b).first())
        assertEquals(history.size, history.map { it.first }.toSet().size) // one point per minute
    }

    @Test
    fun theReplacedSensorsHistoricStreamIsCutToo() {
        // B was out of reach from 23:30 to 01:00: A's historic points must not fill that hole.
        val startB = noon + 10 * hour
        val end = startB + 6 * hour
        val a = sensor(noon, 100, minutes(noon + hour, startB + hour), fiveMinutes(noon + hour, end))
        val b = sensor(startB, 200, minutes(startB + hour, startB + 90 * minute) + minutes(startB + 3 * hour, end))
        val history = history(a, b)
        assertTrue(history.of(a).all { it < startB + hour })
        assertEquals(b.poll.map { it[0] }, history.of(b))
    }

    @Test
    fun aSensorTakenOverWithItsHistoryCountsFromItsFirstLiveReading() {
        // B was started on another device at 14:00 and taken over at 20:00: live from then on, its
        // historic stream reaching back to 15:00 — into the time A was delivering here.
        val startB = noon + 2 * hour
        val firstB = startB + hour
        val takenOver = noon + 8 * hour
        val end = noon + 12 * hour
        val a = sensor(noon - 100 * hour, 100, minutes(noon, end))
        val b = sensor(startB, 200, minutes(takenOver, end, offset = 20_000L), fiveMinutes(firstB, end))
        val history = history(a, b)
        // A's minute readings stay up to the takeover: what B brought along from before only
        // fills holes, and A has none.
        assertEquals(minutes(noon, takenOver + minute).toList(), history.of(a))
        assertEquals(b.poll.map { it[0] }, history.of(b))
    }

    @Test
    fun aLaterSensorThatHasStoppedNoLongerHidesTheOneStillWorn() {
        // B is put on at 22:00, delivers from 23:00 with an hour out of reach, and fails at 04:00.
        // A was never taken off and delivers all along, and on after B.
        val startB = noon + 10 * hour
        val firstB = startB + hour
        val lastB = startB + 6 * hour
        val end = startB + 24 * hour
        val a = sensor(noon, 100, minutes(noon + hour, end), fiveMinutes(noon + hour, end))
        val b = sensor(startB, 200, minutes(firstB, startB + 2 * hour) + minutes(startB + 3 * hour, lastB + minute))
        for (history in listOf(history(a, b), history(b, a))) {
            // While B was in use it stands alone, its hole included; a quarter of an hour after
            // its last reading A counts again.
            val ofA = history.of(a)
            assertEquals(a.poll.map { it[0] }.filter { it < firstB || it > lastB + tail }, ofA)
            assertTrue(ofA.none { it in firstB..lastB + tail })
            assertEquals(b.poll.map { it[0] }, history.of(b))
            assertEquals(100L, history.last().second)
            assertEquals(end - minute, history.last().first)
        }
    }

    @Test
    fun ofThreeSensorsEachIsCutByTheNextOne() {
        // A, B at 20:00, C at 07:00 — none deactivated, all three transmitting to the end.
        val end = noon + 30 * hour
        val a = sensor(noon, 100, minutes(noon + hour, end))
        val b = sensor(noon + 8 * hour, 200, minutes(noon + 9 * hour, end, offset = 20_000L))
        val c = sensor(noon + 19 * hour, 300, minutes(noon + 20 * hour, end, offset = 40_000L))
        for (history in listOf(history(a, b, c), history(c, b, a), history(b, c, a))) {
            assertEquals(minutes(noon + hour, noon + 9 * hour + minute).toList(), history.of(a))
            assertEquals(b.poll.map { it[0] }.filter { it < noon + 20 * hour + 40_000L }, history.of(b))
            assertEquals(c.poll.map { it[0] }, history.of(c))
            assertEquals(history.sortedBy { it.first }, history)
        }
    }

    @Test
    fun aLaterSensorWhoseReadingsBeginFirstCutsBothBeforeIt() {
        // B, activated at 20:00, delivered its first reading only at 09:00; C, activated at 22:00,
        // has been delivering since 23:00. From there on C alone: nothing of B is left.
        val end = noon + 30 * hour
        val a = sensor(noon, 100, minutes(noon + hour, end))
        val b = sensor(noon + 8 * hour, 200, minutes(noon + 21 * hour, end, offset = 20_000L))
        val c = sensor(noon + 10 * hour, 300, minutes(noon + 11 * hour, end, offset = 40_000L))
        for (history in listOf(history(a, b, c), history(c, b, a))) {
            assertEquals(minutes(noon + hour, noon + 11 * hour + minute).toList(), history.of(a))
            assertEquals(emptyList<Long>(), history.of(b))
            assertEquals(c.poll.map { it[0] }, history.of(c))
        }
    }

    // --- in doubt, keep ---

    @Test
    fun aSensorWhoseStartIsNotKnownIsNeitherCutNorCuts() {
        val a = sensor(noon, 100, minutes(noon + hour, noon + 30 * hour))
        val unknownNew = sensor(0L, 200, minutes(noon + 11 * hour, noon + 30 * hour, offset = 20_000L))
        assertEquals(asBefore(a, unknownNew), history(a, unknownNew))
        val unknownOld = sensor(0L, 100, minutes(noon + hour, noon + 30 * hour))
        val b = sensor(noon + 10 * hour, 200, minutes(noon + 11 * hour, noon + 30 * hour, offset = 20_000L))
        assertEquals(asBefore(unknownOld, b), history(unknownOld, b))
    }

    @Test
    fun sensorsWithTheSameStartBothStay() {
        val a = sensor(noon, 100, minutes(noon + hour, noon + 5 * hour))
        val b = sensor(noon, 200, minutes(noon + 2 * hour, noon + 5 * hour, offset = 20_000L))
        assertEquals(asBefore(a, b), history(a, b))
    }

    @Test
    fun aReadingTimedBeforeItsOwnSensorsStartBeginsNothing() {
        // One reading of B timed a year before B was started (a wrong clock): taken for B's first
        // one, it would cut away all of A.
        val startB = noon + 10 * hour
        val a = sensor(noon, 100, minutes(noon + hour, startB + 3 * hour))
        val stray = noon - 365 * 24 * hour
        val b = sensor(startB, 200, listOf(stray) + minutes(startB + hour, startB + 3 * hour, offset = 20_000L))
        val history = history(a, b)
        assertEquals(minutes(noon + hour, startB + hour + minute).toList(), history.of(a))
        assertEquals(b.poll.map { it[0] }, history.of(b))
    }

    // --- overlapSpans ---

    @Test
    fun aSensorIsLeftOutWhileAnyLaterActivatedOneDelivers() {
        // Three sensors in store order B, A, C.
        val starts = longArrayOf(noon + 10 * hour, noon, noon + 20 * hour)
        val firsts = longArrayOf(noon + 11 * hour, noon + hour, noon + 21 * hour)
        val lasts = longArrayOf(noon + 30 * hour, noon + 30 * hour, noon + 30 * hour + minute)
        val spans = Engine.overlapSpans(starts, firsts, lasts)
        assertArrayEquals(longArrayOf(firsts[2], lasts[2] + tail), spans[0]) // B: while C delivers
        assertArrayEquals(longArrayOf(firsts[0], lasts[0] + tail, firsts[2], lasts[2] + tail), spans[1]) // A: B and C
        assertArrayEquals(LongArray(0), spans[2]) // C: the latest
        // C has no live reading yet: it hides nothing.
        firsts[2] = none
        val warmingUp = Engine.overlapSpans(starts, firsts, lasts)
        assertArrayEquals(LongArray(0), warmingUp[0])
        assertArrayEquals(longArrayOf(firsts[0], lasts[0] + tail), warmingUp[1])
    }

    @Test
    fun nothingIsLeftOutWithoutAKnownOrder() {
        val firsts = longArrayOf(noon + hour, noon + 11 * hour)
        val lasts = longArrayOf(noon + 20 * hour, noon + 20 * hour)
        fun spans(vararg starts: Long) = Engine.overlapSpans(starts, firsts, lasts).map { it.toList() }
        assertEquals(listOf(emptyList<Long>(), emptyList()), spans(0L, noon + 10 * hour))
        assertEquals(listOf(emptyList<Long>(), emptyList()), spans(noon, 0L))
        assertEquals(listOf(emptyList<Long>(), emptyList()), spans(noon, noon))
        assertEquals(0, Engine.overlapSpans(LongArray(0), LongArray(0), LongArray(0)).size)
    }
}
