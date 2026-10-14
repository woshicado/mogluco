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

import de.mogluco.ui.util.localDaysBetween
import java.util.concurrent.TimeUnit

/**
 * A reported sensor fault. For Libre 3 the engine surfaces a numeric status; [meaning]
 * translates the common ones into plain language for the home-screen warning banner.
 */
data class SensorError(
    val code: Int,
    val title: String,
    val meaning: String,
) {
    companion object {
        /** Plain-language descriptions of the Libre 3 sensor error/status codes. */
        fun libre3(code: Int): SensorError = when (code) {
            1 -> SensorError(code, "Sensor too hot", "The sensor is above its operating temperature. Move somewhere cooler and wait for the next reading.")
            2 -> SensorError(code, "Sensor too cold", "The sensor is below its operating temperature. Warm up and wait for the next reading.")
            3 -> SensorError(code, "Glucose unavailable", "The sensor can't produce a reading right now. This usually clears on its own within a few minutes.")
            4 -> SensorError(code, "Glucose high", "Glucose is above the sensor's measurable range (>27.8 mmol/L / 500 mg/dL).")
            5 -> SensorError(code, "Glucose low", "Glucose is below the sensor's measurable range (<2.2 mmol/L / 40 mg/dL).")
            6 -> SensorError(code, "Sensor error", "The sensor reported a fault. If it persists, the sensor may need to be replaced.")
            else -> SensorError(code, "Sensor error $code", "The sensor reported an unrecognised status. Check the sensor and try scanning again.")
        }

        /**
         * Maps the engine's per-reading alarm/state code to a sensor error, or null when the
         * reading is a normal value (or a user-threshold high/low *glucose* alarm, which is not a
         * sensor fault). Only the codes that mean "the sensor can't give a usable number" are
         * surfaced: 3 = unavailable, 4 = above measurable range, 5 = below measurable range.
         *
         * Verified against the native engine (Common/src/main/cpp/g.cpp: the is* constants +
         * getalarmonly): isAgain=3, isHighest=4, isLowest=5 are the "can't measure" codes, while
         * isHigh=6 / isLow=7 / isVeryHigh=16 / isVeryLow=17 / isPreHigh=18 / isPreLow=19 are
         * user-threshold glucose alarms and correctly fall through to null here — they are handled
         * as glucose alarms (AlarmController), not sensor-fault banners.
         */
        fun fromGlucoseAlarm(alarm: Int): SensorError? = when (alarm) {
            3 -> SensorError(alarm, "Glucose unavailable", "The sensor can't produce a reading right now. This usually clears on its own within a few minutes.")
            4 -> SensorError(alarm, "Glucose above range", "Glucose is above the sensor's measurable range (over 27.8 mmol/L / 500 mg/dL).")
            5 -> SensorError(alarm, "Glucose below range", "Glucose is below the sensor's measurable range (under 2.2 mmol/L / 40 mg/dL).")
            else -> null
        }

        /**
         * Maps the engine's sensor-state code (from `Sensors.sensorState`) to a hardware fault, or
         * null when the sensor is healthy/warming. 6 = a defective sensor (the first reading after
         * warm-up was out of range), 7 = replace, 9 = ended. Code 8 (not ready) is left to the
         * warm-up note, not the error banner.
         */
        fun fromSensorState(state: Int): SensorError? = when (state) {
            6 -> SensorError(state, "Sensor error", "The sensor reported a fault — its readings are out of range. If it persists, the sensor is likely defective and should be replaced.")
            7 -> SensorError(state, "Replace sensor", "The sensor has failed and needs to be replaced.")
            9 -> SensorError(state, "Sensor ended", "This sensor has reached the end of its life. Activate a new one.")
            else -> null
        }

        /**
         * Maps a BLE disconnect into a connection/handshake error, or null for a benign drop.
         *
         * This surfaces the failures behind upstream issue #405: Mogluco connects to the sensor
         * but the Libre 3 secure handshake never completes, so no glucose data ever flows. Without
         * this the home screen showed nothing — a sensor stuck "warming up" forever with no clue.
         *
         * @param status the GATT status. 0 = success and 22 = local termination are benign on
         *   their own; 8 = link timeout, 19 = sensor dropped the link, 133 = connection failed,
         *   147 = secure handshake rejected.
         * @param detail the failed handshake stage from the engine, or null. A non-null detail is
         *   a conclusive failure even when [status] is 0 (the app aborted the handshake locally,
         *   e.g. on an unexpected SEC_CHAR_CHALLENGE_DATA length).
         */
        fun fromConnection(status: Int, detail: String?): SensorError? {
            val benignStatus = status == 0 || status == 22
            if (benignStatus && detail == null) return null
            val cause = when (status) {
                8 -> "the link timed out"
                19 -> "the sensor dropped the connection"
                133 -> "the connection failed"
                147 -> "the sensor rejected the secure handshake"
                0, 22 -> "the handshake was aborted"
                else -> "BLE status $status"
            }
            val meaning = buildString {
                append("Mogluco reached the sensor but couldn't finish the Libre 3 secure handshake (")
                append(cause)
                append("), so no glucose data can come through. ")
                if (!detail.isNullOrBlank()) append("Stage: $detail. ")
                append(
                    "Try turning Bluetooth off and on, then reboot, and make sure no other app or " +
                        "phone is connected to this sensor. If it keeps happening across sensors, " +
                        "this is the known Libre 3 handshake problem (see issue #405)."
                )
            }
            return SensorError(code = status, title = "Sensor setup failed", meaning = meaning)
        }
    }
}

/**
 * Identity and lifecycle of the active sensor, surfaced on the home and calendar screens.
 *
 * @param id sensor serial / identifier
 * @param activationMillis when the sensor was activated (warm-up start)
 * @param lifetimeDays nominal sensor life: the engine's figure (14 or 15 days) where it knows one,
 *   for the current sensor as for past ones; otherwise the default (Libre 3 Plus = 15 days)
 * @param error a currently-reported fault, or null when healthy
 * @param endMillis for a past sensor, when its life actually ended as far as the store knows (it
 *   failed or was replaced before its nominal expiry); null — always for the current sensor —
 *   means the nominal [lifetimeDays]
 */
data class SensorInfo(
    val id: String,
    val activationMillis: Long,
    val lifetimeDays: Int = 15,
    val error: SensorError? = null,
    val endMillis: Long? = null,
) {
    fun expiryMillis(): Long =
        endMillis ?: (activationMillis + TimeUnit.DAYS.toMillis(lifetimeDays.toLong()))

    /** End of the warm-up period (Libre 3 warms up for 60 minutes after activation). */
    fun warmupEndMillis(): Long = activationMillis + WARMUP_MS

    /** True while the sensor is still warming up and not yet sending readings. */
    fun isWarmingUp(now: Long = System.currentTimeMillis()): Boolean = now < warmupEndMillis()

    /**
     * 1-based sensor day, counted in LOCAL calendar days: the activation date is day 1, so the
     * morning after an evening activation is already day 2. This is the one day-numbering
     * convention for the whole app — the calendar's per-date badge ([sensorDayNumber]) delegates
     * here, so Home/Settings/notification always agree with it. Clamped to [1, lifetimeDays]: a
     * sensor activated mid-day spans lifetimeDays+1 calendar dates (the last partial date reads as
     * the final day), and a sensor worn past expiry reads "15/15" rather than an impossible
     * "17/15" — matching the calendar badge, which likewise never exceeds lifetimeDays.
     */
    fun dayOfLife(now: Long = System.currentTimeMillis()): Int {
        val day = localDaysBetween(activationMillis, now) + 1
        return day.coerceIn(1, lifetimeDays)
    }

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiryMillis()

    /** Progress through the sensor's life in [0,1]. */
    fun lifeFraction(now: Long = System.currentTimeMillis()): Float =
        ((now - activationMillis).toFloat() / TimeUnit.DAYS.toMillis(lifetimeDays.toLong()))
            .coerceIn(0f, 1f)

    companion object {
        /** Libre 3 warm-up duration after activation. */
        val WARMUP_MS: Long = TimeUnit.MINUTES.toMillis(60)
    }
}
