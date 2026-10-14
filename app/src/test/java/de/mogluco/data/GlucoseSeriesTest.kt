package de.mogluco.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The pure series functions of :shared's GlucoseReading.kt: the smoothing EMA and the gap fill. */
class GlucoseSeriesTest {

    private val minute = 60_000L
    private val gap = 15 * minute
    private val noon = 1_700_000_000_000L

    private fun series(startMillis: Long, stepMillis: Long, vararg mgdl: Double): List<GlucosePoint> =
        mgdl.mapIndexed { i, v -> GlucosePoint(startMillis + i * stepMillis, v) }

    /** The EMA as it was before it knew about gaps. */
    private fun plainEma(points: List<GlucosePoint>, alpha: Double): List<Double> {
        var ema = points.first().rawMgdl
        return points.map { p ->
            ema = alpha * p.rawMgdl + (1 - alpha) * ema
            ema
        }
    }

    // --- smoothed ---

    @Test
    fun gapFreeMinuteSeriesIsSmoothedExactlyAsBefore() {
        val points = series(noon, minute, 101.0, 104.0, 99.0, 131.0, 150.0, 149.0, 97.0, 88.0)
        val smoothed = points.smoothed(enabled = true, alpha = 0.4, gapMillis = gap)
        assertEquals(plainEma(points, 0.4), smoothed.map { it.calibratedMgdl }) // bit for bit
        assertEquals(points.map { it.rawMgdl }, smoothed.map { it.rawMgdl })
    }

    @Test
    fun fiveMinuteStretchIsNotAGap() {
        val points = series(noon, 5 * minute, 120.0, 140.0, 160.0, 150.0)
        val smoothed = points.smoothed(enabled = true, alpha = 0.3, gapMillis = gap)
        assertEquals(plainEma(points, 0.3), smoothed.map { it.calibratedMgdl })
    }

    @Test
    fun emaStartsAfreshAfterAGap() {
        // Last value before a warm-up 180, first after it 90: carried over, the trace read
        // 144, 122, 109, 102 before it reached the real level.
        val before = series(noon, minute, 180.0, 180.0, 180.0)
        val after = series(noon + 62 * minute, minute, 90.0, 90.0, 90.0, 90.0)
        val smoothed = (before + after).smoothed(enabled = true, alpha = 0.4, gapMillis = gap)
        smoothed.take(3).forEach { assertEquals(180.0, it.calibratedMgdl, 1e-9) }
        smoothed.drop(3).forEach { assertEquals(90.0, it.calibratedMgdl, 1e-9) }
    }

    @Test
    fun afterAGapTheSeriesIsSmoothedLikeASeriesOfItsOwn() {
        val before = series(noon, minute, 180.0, 170.0)
        val after = series(noon + 3 * 60 * minute, minute, 90.0, 110.0, 100.0)
        val smoothed = (before + after).smoothed(enabled = true, alpha = 0.4, gapMillis = gap)
        assertEquals(plainEma(after, 0.4), smoothed.drop(2).map { it.calibratedMgdl })
    }

    @Test
    fun aGapOfExactlyTheThresholdStillCarriesOver() {
        val points = listOf(GlucosePoint(noon, 180.0), GlucosePoint(noon + gap, 90.0))
        val smoothed = points.smoothed(enabled = true, alpha = 0.4, gapMillis = gap)
        assertEquals(144.0, smoothed.last().calibratedMgdl, 1e-9)
    }

    @Test
    fun smoothingOffIsRaw() {
        val points = listOf(GlucosePoint(noon, 180.0, 150.0), GlucosePoint(noon + minute, 90.0, 120.0))
        val raw = points.smoothed(enabled = false, gapMillis = gap)
        assertEquals(listOf(180.0, 90.0), raw.map { it.calibratedMgdl })
    }

    // --- smoothedAfter ---

    private fun List<GlucosePoint>.ema() = smoothed(enabled = true, alpha = 0.4, gapMillis = gap)

    /** The smoothed value of a reading arriving while [held] is the series and its last point the running EMA. */
    private fun GlucosePoint.arriving(held: List<GlucosePoint>, latest: GlucosePoint? = held.lastOrNull()) =
        smoothedAfter(latest, held, 0.4, gap).calibratedMgdl

    @Test
    fun aLiveReadingContinuesTheRunningEma() {
        val held = series(noon, minute, 101.0, 104.0, 99.0, 131.0).ema()
        val reading = GlucosePoint(noon + 4 * minute, 150.0)
        assertEquals((held + reading).ema().last(), reading.smoothedAfter(held.last(), held, 0.4, gap)) // bit for bit
        // A refresh of the newest slot counts as live, too.
        val again = GlucosePoint(held.last().epochMillis, 140.0)
        assertEquals(0.4 * 140.0 + 0.6 * held.last().calibratedMgdl, again.arriving(held), 0.0)
    }

    @Test
    fun aLiveReadingAfterAGapStartsAfresh() {
        val held = series(noon, minute, 180.0, 180.0).ema()
        assertEquals(144.0, GlucosePoint(held.last().epochMillis + gap, 90.0).arriving(held), 1e-9)
        assertEquals(90.0, GlucosePoint(held.last().epochMillis + gap + 1, 90.0).arriving(held), 0.0)
        assertEquals(90.0, GlucosePoint(noon, 90.0).arriving(emptyList()), 0.0)
    }

    @Test
    fun aReplayedOlderReadingIsSmoothedFromThePointBeforeIt() {
        // Level 100, then 200 for the last minutes: the running EMA is near 200. The engine replays
        // 12:02 and a reading we missed at 12:02:30 — both belong to the 100 level.
        val held = series(noon, minute, 100.0, 104.0, 98.0, 100.0, 200.0, 200.0, 200.0, 200.0).ema()
        // A reading we hold already comes out as it is held.
        val replayed = GlucosePoint(held[2].epochMillis, 98.0)
        assertEquals(held[2], replayed.smoothedAfter(held.last(), held, 0.4, gap))
        val missed = GlucosePoint(noon + 2 * minute + 30_000L, 110.0)
        assertEquals(0.4 * 110.0 + 0.6 * held[2].calibratedMgdl, missed.arriving(held), 0.0)
    }

    @Test
    fun anOlderReadingWithNothingCloseBeforeItStartsAfresh() {
        val held = series(noon, minute, 200.0, 200.0, 200.0).ema()
        // Older than everything we hold: the newer points say nothing about it.
        assertEquals(100.0, GlucosePoint(noon - minute, 100.0).arriving(held), 0.0)
        // The point before it lies beyond a gap.
        val all = held + series(noon + 60 * minute, minute, 150.0, 150.0).ema()
        assertEquals(100.0, GlucosePoint(noon + 40 * minute, 100.0).arriving(all), 0.0)
    }

    // --- filledFrom ---

    private val distance = 3 * minute

    @Test
    fun fillsAHoleFromTheCandidates() {
        // Ours: 12:00–12:05 and 14:00–14:05; the peer has every minute from 12:00 to 14:05.
        val ours = series(noon, minute, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0) +
            series(noon + 120 * minute, minute, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0)
        val peer = (0..125).map { GlucosePoint(noon + it * minute, 200.0) }
        val filled = ours.filledFrom(peer, distance)
        // Everything of ours is still there, unchanged …
        assertEquals(ours, filled.filter { it.rawMgdl == 100.0 })
        // … and the hole is filled, keeping the distance from our points at both ends.
        val added = filled.filter { it.rawMgdl == 200.0 }.map { (it.epochMillis - noon) / minute }
        assertEquals((9L..116L).toList(), added)
        assertEquals(filled.sortedBy { it.epochMillis }, filled)
        assertEquals(filled.size, filled.map { it.epochMillis }.toSet().size)
    }

    @Test
    fun aBatchWithAHoleOfItsOwnDeletesNothing() {
        // The peer was the one out of reach from 12:10 to 12:50; we measured through it.
        val ours = (0..60).map { GlucosePoint(noon + it * minute, 100.0) }
        val peer = (0..10).map { GlucosePoint(noon + it * minute, 200.0) } +
            (50..60).map { GlucosePoint(noon + it * minute, 200.0) }
        assertSame(ours, ours.filledFrom(peer, distance))
    }

    @Test
    fun candidatesNextToAnExistingPointAreLeftOut() {
        // The same readings, timed a few seconds apart by the two devices: no near-duplicates.
        val ours = (0..10).map { GlucosePoint(noon + it * minute, 100.0) }
        val peer = (0..10).map { GlucosePoint(noon + it * minute + 20_000L, 200.0) }
        assertSame(ours, ours.filledFrom(peer, distance))
        // A 5-minute stretch is complete too: a point between two of them is within the distance.
        val historic = (0..6).map { GlucosePoint(noon + it * 5 * minute, 100.0) }
        assertSame(historic, historic.filledFrom(peer, distance))
    }

    @Test
    fun candidatesBeforeAndAfterTheSeriesAreAdded() {
        val ours = series(noon, minute, 100.0, 100.0)
        val peer = listOf(
            GlucosePoint(noon - 10 * minute, 200.0),
            GlucosePoint(noon - 2 * minute, 200.0), // too close to our first point
            GlucosePoint(noon + 3 * minute, 200.0), // too close to our last point
            GlucosePoint(noon + 10 * minute, 200.0),
        )
        val filled = ours.filledFrom(peer, distance)
        assertEquals(
            listOf(noon - 10 * minute, noon, noon + minute, noon + 10 * minute),
            filled.map { it.epochMillis },
        )
    }

    @Test
    fun emptySides() {
        val some = series(noon, minute, 100.0, 100.0)
        assertSame(some, some.filledFrom(emptyList(), distance))
        assertSame(some, emptyList<GlucosePoint>().filledFrom(some, distance))
    }
}
