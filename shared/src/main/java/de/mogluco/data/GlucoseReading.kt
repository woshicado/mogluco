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

import kotlin.math.atan
import kotlin.math.roundToInt

/** Glucose unit. The native engine stores mg/dL; mmol/L is a display conversion. */
enum class GlucoseUnit(val label: String) {
    MGDL("mg/dL"),
    MMOLL("mmol/L");

    /** Convert a raw mg/dL value into this unit. */
    fun fromMgdl(mgdl: Double): Double = when (this) {
        MGDL -> mgdl
        MMOLL -> mgdl / 18.0182
    }

    /** Format a mg/dL value for display in this unit. */
    fun format(mgdl: Double): String = when (this) {
        MGDL -> fromMgdl(mgdl).roundToInt().toString()
        MMOLL -> String.format("%.1f", fromMgdl(mgdl))
    }
}

/** Which readings the user wants shown on the graph and in lists. */
enum class ReadingDisplay { RAW, SMOOTHED, BOTH }

/** Direction of glucose change, mirroring the trend arrows the native engine computes. */
enum class TrendArrow(val glyph: String) {
    RISING_FAST("⇈"),
    RISING("↑"),
    RISING_SLOW("↗"),
    FLAT("→"),
    FALLING_SLOW("↘"),
    FALLING("↓"),
    FALLING_FAST("⇊"),
    UNKNOWN("·");

    companion object {
        /**
         * Sensitivity of the drawn arrow's tilt: the rate (mg/dL per minute) is divided by this
         * before atan, so a *smaller* value tilts the arrow more steeply for the same rate. At 0.5 a
         * 0.5 mg/dL/min drift reads ~45°, 1 mg/dL/min ~63°, and ≥~1.9 pegs at the ±75° clamp — so
         * even a modest rise/fall looks clearly directional instead of nearly flat.
         */
        private const val ANGLE_RATE_SCALE = 0.5

        /** Map rate of change (mg/dL per minute) to a trend arrow (Dexcom-style thresholds). */
        fun fromRate(rate: Double): TrendArrow = when {
            rate >= 3.0 -> RISING_FAST
            rate >= 2.0 -> RISING
            rate >= 1.0 -> RISING_SLOW
            rate > -1.0 -> FLAT
            rate > -2.0 -> FALLING_SLOW
            rate > -3.0 -> FALLING
            else -> FALLING_FAST
        }

        /**
         * Rotation in degrees for a rate of change (mg/dL per minute), where a flat trend (→, an
         * arrow that natively points right) is 0°. Rising tilts up (negative, since screen-y grows
         * downward), falling tilts down; clamped so steep rates stop just short of vertical. The one
         * place the rate→angle mapping lives — shared by the home card, chart tooltip, watch
         * complication/tile, and AOD so every arrow agrees.
         */
        fun angleDegrees(rate: Double): Float =
            (-Math.toDegrees(atan(rate / ANGLE_RATE_SCALE)).toFloat()).coerceIn(-75f, 75f)
    }
}

/** Where the current reading sits relative to the user's target range. */
enum class GlucoseState { URGENT_LOW, LOW, IN_RANGE, HIGH, URGENT_HIGH }

/**
 * Classify a mg/dL value against the target range. The single source of truth for the
 * urgent-low (targetLow − 15) and urgent-high (targetHigh + 60) thresholds, shared by the
 * reading card, the chart band coloring and the notification so they can never disagree.
 */
fun glucoseState(mgdl: Double, targetLow: Double, targetHigh: Double): GlucoseState = when {
    mgdl < targetLow - 15 -> GlucoseState.URGENT_LOW
    mgdl < targetLow -> GlucoseState.LOW
    mgdl > targetHigh + 60 -> GlucoseState.URGENT_HIGH
    mgdl > targetHigh -> GlucoseState.HIGH
    else -> GlucoseState.IN_RANGE
}

/**
 * A single historical point for the graph. The engine reports a raw sensor value and,
 * for auto-calibrating sensors, a calibrated value; both are kept so the graph can draw
 * both lines and the inspector tooltip can show each.
 */
data class GlucosePoint(
    val epochMillis: Long,
    val rawMgdl: Double,
    val calibratedMgdl: Double = rawMgdl,
)

/**
 * Returns the points with [GlucosePoint.calibratedMgdl] set to a causal EMA of the raw values (a
 * light smoothing of sensor noise) when [enabled], or equal to raw when off. Receiver must be
 * time-sorted ascending. Alpha ≈ 0.4 blends the last ~2–3 readings — enough to calm jitter without
 * lagging the trend. This is the "smoothed" trace shown alongside "raw".
 *
 * The EMA starts afresh after a gap longer than [gapMillis] (a warm-up, a night out of range, a
 * sensor change): the value before the gap says nothing about the one after it, and carried over
 * it bent the first readings after the gap towards the old level. Within a gap-free series the
 * result is unchanged. [alpha] is per sample, not per minute — a stretch of 5-minute historic
 * points is therefore smoothed over a longer time than the 1-minute live trace.
 */
fun List<GlucosePoint>.smoothed(enabled: Boolean, alpha: Double = 0.4, gapMillis: Long): List<GlucosePoint> {
    if (isEmpty()) return this
    if (!enabled) return map { if (it.calibratedMgdl == it.rawMgdl) it else it.copy(calibratedMgdl = it.rawMgdl) }
    var ema = first().rawMgdl
    var prevMillis = first().epochMillis
    return map { p ->
        if (p.epochMillis - prevMillis > gapMillis) ema = p.rawMgdl // as for the very first point
        prevMillis = p.epochMillis
        ema = alpha * p.rawMgdl + (1 - alpha) * ema
        p.copy(calibratedMgdl = ema)
    }
}

/**
 * The receiver, a single reading arriving on the live path, smoothed as [smoothed] smooths a point
 * within its series: the EMA continued from the point before it, or started afresh at this reading
 * when that point is more than [gapMillis] away (or there is none). For a reading at least as new
 * as the running EMA [latest] the point before it is [latest]. An older one — the engine replays
 * readings on a reconnect — has nothing to do with [latest]: it continues from the point before it
 * in [series] (time-sorted ascending) instead. The points after an older one keep their values
 * until the series is next smoothed as a whole.
 */
fun GlucosePoint.smoothedAfter(
    latest: GlucosePoint?,
    series: List<GlucosePoint>,
    alpha: Double,
    gapMillis: Long,
): GlucosePoint {
    val before = if (latest == null || epochMillis >= latest.epochMillis) {
        latest
    } else {
        series.getOrNull(series.lowerBoundByTime(epochMillis) - 1)
    }
    val previous = before?.takeIf { epochMillis - it.epochMillis <= gapMillis }
    return copy(calibratedMgdl = alpha * rawMgdl + (1 - alpha) * (previous?.calibratedMgdl ?: rawMgdl))
}

/**
 * The receiver plus those [candidates] that lie in its gaps: a candidate is added only when the
 * receiver has no point within [minDistanceMillis] of it. Nothing in the receiver is dropped or
 * replaced, and a candidate never lands next to an existing point, so two sources that time the
 * same reading a little differently cannot leave near-duplicates. Both lists must be time-sorted
 * ascending with unique timestamps; so is the result. Returns the receiver itself when nothing
 * was added.
 */
fun List<GlucosePoint>.filledFrom(candidates: List<GlucosePoint>, minDistanceMillis: Long): List<GlucosePoint> {
    if (candidates.isEmpty()) return this
    if (isEmpty()) return candidates
    var out: ArrayList<GlucosePoint>? = null // allocated with the first candidate that fits
    var copied = 0 // receiver points already in [out]
    var i = 0 // first receiver point later than the candidate at hand
    for (c in candidates) {
        val t = c.epochMillis
        while (i < size && this[i].epochMillis <= t) i++
        val farFromPrevious = i == 0 || t - this[i - 1].epochMillis > minDistanceMillis
        val farFromNext = i == size || this[i].epochMillis - t > minDistanceMillis
        if (farFromPrevious && farFromNext) {
            val o = out ?: ArrayList<GlucosePoint>(size + 16).also { out = it }
            while (copied < i) o.add(this[copied++])
            o.add(c)
        }
    }
    val o = out ?: return this
    while (copied < size) o.add(this[copied++])
    return o
}

/**
 * The points whose timestamps fall within [[startMillis], [endMillis]] (inclusive). Assumes the
 * receiver is sorted ascending by [GlucosePoint.epochMillis] — the repository's invariant — so it
 * binary-searches the bounds (O(log n + k)) instead of scanning the whole history (O(n)). Returns a
 * sublist *view*, so it allocates nothing; callers must not mutate the backing list (it's
 * immutable). This is how each chart/list renders only its visible window rather than the full
 * (potentially year-long, multi-sensor) history.
 */
fun List<GlucosePoint>.windowed(startMillis: Long, endMillis: Long): List<GlucosePoint> {
    if (isEmpty() || startMillis > endMillis) return emptyList()
    val from = lowerBoundByTime(startMillis) // first index with epochMillis >= start
    val to = lowerBoundByTime(endMillis + 1) // first index with epochMillis > end
    return if (from >= to) emptyList() else subList(from, to)
}

/** Smallest index `i` such that `this[i].epochMillis >= t` (or `size` if none). Receiver must be sorted. */
private fun List<GlucosePoint>.lowerBoundByTime(t: Long): Int {
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].epochMillis < t) lo = mid + 1 else hi = mid
    }
    return lo
}

/**
 * A single glucose reading as the UI consumes it.
 *
 * Age/staleness are derived from [epochMillis] against the current time at render, so a
 * reading correctly ages as time passes without re-emitting.
 *
 * @param calibratedMgdl the primary displayed value, in mg/dL (the engine's native unit)
 * @param rawMgdl the uncalibrated sensor value, in mg/dL
 * @param epochMillis timestamp of the measurement
 * @param trend direction of change
 * @param ratePerMin rate of change in mg/dL per minute (drives the tilted trend arrow)
 */
data class GlucoseReading(
    val calibratedMgdl: Double,
    val rawMgdl: Double = calibratedMgdl,
    val epochMillis: Long,
    val trend: TrendArrow,
    val ratePerMin: Double = 0.0,
    /** The sensor it was taken by (its serial), null where that is not known. */
    val sensorId: String? = null,
) {
    /** Primary displayed value (calibrated). */
    val mgdl: Double get() = calibratedMgdl

    fun value(unit: GlucoseUnit): Double = unit.fromMgdl(calibratedMgdl)
    fun rawValue(unit: GlucoseUnit): Double = unit.fromMgdl(rawMgdl)

    /** Whether the calibrated and raw values differ enough to be worth showing both. */
    val isCalibrated: Boolean get() = kotlin.math.abs(calibratedMgdl - rawMgdl) >= 1.0

    fun ageMillis(now: Long = System.currentTimeMillis()): Long =
        (now - epochMillis).coerceAtLeast(0)

    /** Readings older than this are considered stale (sensor likely disconnected). */
    fun isStale(now: Long = System.currentTimeMillis(), thresholdMillis: Long = 6 * 60_000): Boolean =
        ageMillis(now) > thresholdMillis

    fun state(targetLow: Double, targetHigh: Double): GlucoseState =
        glucoseState(calibratedMgdl, targetLow, targetHigh)

    /** This reading's trend-arrow rotation in degrees (see [TrendArrow.angleDegrees]). */
    val trendAngleDegrees: Float
        get() = TrendArrow.angleDegrees(ratePerMin)
}
