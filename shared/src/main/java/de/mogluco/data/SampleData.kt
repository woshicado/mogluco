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

import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic synthetic data so the redesigned UI can be built and visually verified
 * without a physical sensor (none is available on the emulator). Generates a realistic
 * three-day glucose trace with raw + calibrated lines, an active sensor partway through
 * its life, and a spread of meal / insulin / activity events with notes.
 *
 * This is the single source the [MockGlucoseRepository] and [MockEventRepository] read,
 * so the graph, header, and event list all agree.
 */
object SampleData {

    const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val STEP_MS = 5 * 60 * 1000L // one point every 5 minutes
    private val rng = Random(42)

    /**
     * A short sensor history so the Calendar can show which sensor was active on any given
     * day. The newest (current) sensor was activated 6 days ago; the one before it ran the
     * preceding 15 days. IDs differ so day-by-day lookup is visibly correct.
     */
    fun sensorHistory(now: Long = System.currentTimeMillis()): List<SensorInfo> = listOf(
        SensorInfo(id = "3MH00CK4F2A", activationMillis = now - 6 * DAY_MS, lifetimeDays = 15),
        SensorInfo(id = "3MH00BX2E9C", activationMillis = now - 20 * DAY_MS, lifetimeDays = 15),
    )

    /** The currently-running sample sensor (activated 6 days into a 15-day life). */
    fun activeSensor(now: Long = System.currentTimeMillis()): SensorInfo = sensorHistory(now).first()

    /** The sensor that was active on the given day, if any (Calendar selected-day header). */
    fun sensorForDay(dayMillis: Long, now: Long = System.currentTimeMillis()): SensorInfo? =
        sensorHistory(now).firstOrNull { dayMillis >= it.activationMillis - DAY_MS && dayMillis < it.expiryMillis() }
            ?: sensorHistory(now).firstOrNull { dayMillis >= it.activationMillis }

    val eventTypes: List<EventType> = listOf(
        EventType(0, "Carbohydrates", EventCategory.CARBS),
        EventType(1, "Fast insulin", EventCategory.INSULIN),
        EventType(2, "Slow insulin", EventCategory.INSULIN),
        EventType(3, "Exercise", EventCategory.ACTIVITY),
        EventType(4, "Note", EventCategory.OTHER),
    )

    /** Three days of 5-minute points, oldest first. */
    fun history(now: Long = System.currentTimeMillis()): List<GlucosePoint> {
        val start = now - 3 * DAY_MS
        val count = ((now - start) / STEP_MS).toInt()
        return (0..count).map { i ->
            val t = start + i * STEP_MS
            val raw = glucoseAt(t, start)
            // Auto-calibration: the calibrated line tracks raw with a gentle gain/offset.
            val calibrated = (raw * 1.04 + 4.0).coerceIn(40.0, 350.0)
            GlucosePoint(epochMillis = t, rawMgdl = raw, calibratedMgdl = calibrated)
        }
    }

    /** The most recent reading, derived from the tail of [history] so they agree. */
    fun latest(now: Long = System.currentTimeMillis()): GlucoseReading {
        val pts = history(now)
        val last = pts.last()
        val prev = pts[pts.size - 6] // ~25 min earlier
        val ratePerMin = (last.calibratedMgdl - prev.calibratedMgdl) /
            ((last.epochMillis - prev.epochMillis) / 60_000.0)
        return GlucoseReading(
            calibratedMgdl = last.calibratedMgdl,
            rawMgdl = last.rawMgdl,
            epochMillis = last.epochMillis,
            trend = TrendArrow.fromRate(ratePerMin),
            ratePerMin = ratePerMin,
        )
    }

    /** Meal / insulin / activity events across the last three days, newest last. */
    fun events(now: Long = System.currentTimeMillis()): List<LoggedEvent> {
        val out = mutableListOf<LoggedEvent>()
        // For each of the last 3 days, lay down breakfast, lunch, dinner + insulin, and a ride.
        for (day in 2 downTo 0) {
            val midnight = now - day * DAY_MS
            fun at(hour: Double) = midnight - ((now - midnight) % DAY_MS) +
                (hour * 60 * 60 * 1000L).toLong()
            // Anchor to the start of that calendar-ish day relative to now.
            val base = now - day * DAY_MS - ((now - day * DAY_MS) % DAY_MS)
            fun tod(hour: Double) = base + (hour * 3_600_000L).toLong()

            out += ev(tod(7.5), 0, 45f, "Breakfast — oats & berries")
            out += ev(tod(7.6), 1, 6f, "Bolus for breakfast")
            out += ev(tod(12.5), 0, 60f, "Lunch — sandwich")
            out += ev(tod(12.6), 1, 8f, "Bolus for lunch")
            if (day == 1) out += ev(tod(17.0), 3, 45f, "Evening bike ride, 45 min")
            out += ev(tod(19.0), 0, 70f, "Dinner — pasta")
            out += ev(tod(19.1), 1, 9f, "Bolus for dinner")
            out += ev(tod(22.5), 2, 18f, "Basal — long acting")
        }
        return out.filter { it.epochMillis in (now - 3 * DAY_MS)..now }.sortedBy { it.epochMillis }
    }

    private fun ev(time: Long, type: Int, value: Float, note: String): LoggedEvent {
        val t = eventTypes[type]
        return LoggedEvent(
            epochMillis = time,
            type = type,
            value = value,
            note = note,
            category = t.category,
            label = t.name,
        )
    }

    /**
     * A plausible mg/dL value at time [t]: a slow daily rhythm plus meal-driven post-prandial
     * bumps around 8:00 / 13:00 / 19:30, with a little noise. Stays mostly in-range but dips
     * low overnight and spikes high after dinner so every glucose-state color is exercised.
     */
    private fun glucoseAt(t: Long, start: Long): Double {
        val minutes = (t - start) / 60_000.0
        val hourOfDay = ((t / 3_600_000.0) % 24)
        val baseline = 120.0 + 28.0 * sin(minutes / 220.0)
        val meals = mealBump(hourOfDay, 8.0, 70.0) +
            mealBump(hourOfDay, 13.0, 55.0) +
            mealBump(hourOfDay, 19.5, 95.0)
        val overnightDip = if (hourOfDay in 2.0..5.0) -30.0 else 0.0
        val noise = (rng.nextDouble() - 0.5) * 8.0
        return (baseline + meals + overnightDip + noise).coerceIn(45.0, 320.0)
    }

    private fun mealBump(hourOfDay: Double, peakHour: Double, magnitude: Double): Double {
        val dh = hourOfDay - peakHour
        if (dh < -0.5 || dh > 3.0) return 0.0
        // Asymmetric rise/fall around the meal.
        val x = dh / 1.3
        return magnitude * Math.exp(-x * x).coerceIn(0.0, 1.0)
    }
}
