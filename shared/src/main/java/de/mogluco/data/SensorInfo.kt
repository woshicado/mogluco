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
 * A fault for the home-screen warning banner: a numeric [code], where it comes from ([source]) and
 * a plain-language [meaning].
 *
 * None of these codes is an error code the sensor sent. Mogluco derives 4–7 and 9 from the
 * sensor's readings and state, and the connection codes are statuses of the phone's Bluetooth
 * stack. [source] says which, so a user reporting a failed sensor to Abbott knows not to quote
 * them as a sensor error code.
 *
 * The texts say only what Mogluco observed, how sure it is of the cause, what it does about it by
 * itself, and from when on the user should act: most of these clear on their own, and a banner
 * that cries "defective sensor" over a passing hiccup teaches the user to ignore all of them.
 *
 * @param sensorFault whether this can mean a failed sensor, so [sourceNote] adds what to report
 *   to Abbott; false for a reading at the edge of the measurable range
 */
data class SensorError(
    val code: Int,
    val title: String,
    val meaning: String,
    val source: Source = Source.APP,
    val sensorFault: Boolean = true,
) {
    /** Where [code] comes from; [note] says so on the banner. */
    enum class Source(val label: String, val note: String) {
        /** Mogluco's own state number, derived from the sensor's readings or state. */
        APP("Mogluco code", "This is Mogluco's own code, not an error code from the sensor."),

        /** A GATT/HCI status from the phone's (or watch's) Bluetooth stack. */
        BLUETOOTH("Bluetooth status", "This is a status from the phone's Bluetooth, not an error code from the sensor."),
    }

    /** The code named by its source, e.g. "Mogluco code 6" or "Bluetooth status 147". */
    val codeLabel: String get() = "${source.label} $code"

    /**
     * The banner's last line: that [code] is not from the sensor, and for a [sensorFault] what to
     * give Abbott instead.
     */
    val sourceNote: String
        get() = if (!sensorFault) source.note else "${source.note} To report a failed sensor to " +
            "Abbott, give its serial number and when the problem started."

    companion object {
        /**
         * Maps the engine's per-reading alarm/state code to a banner, or null. Only a reading at
         * the edge of the measurable range is surfaced: 4 = above it, 5 = below it.
         *
         * Verified against the native engine (Common/src/main/cpp/g.cpp: the is* constants +
         * getalarmonly): isHighest=4 / isLowest=5 come with a reading over 500 / under 40 mg/dL
         * (Libre 3 sends 501 / 39 there). isAgain=3 is the "glucose available again" alarm on the
         * first reading after a gap, so no fault, and isHigh=6 / isLow=7 / isVeryHigh=16 /
         * isVeryLow=17 / isPreHigh=18 / isPreLow=19 are user-threshold glucose alarms, handled
         * by AlarmController: all of these fall through to null.
         */
        fun fromGlucoseAlarm(alarm: Int): SensorError? = when (alarm) {
            4 -> SensorError(alarm, "Glucose above sensor range", "The sensor measured more than 500 mg/dL (27.8 mmol/L), the highest it can measure, so your glucose may be higher than shown. Check it with a blood glucose meter.", sensorFault = false)
            5 -> SensorError(alarm, "Glucose below sensor range", "The sensor measured less than 40 mg/dL (2.2 mmol/L), the lowest it can measure, so your glucose may be lower than shown. Check it with a blood glucose meter.", sensorFault = false)
            else -> null
        }

        /**
         * Maps the engine's sensor-state code (from `Sensors.sensorState`) to a fault, or null when
         * the sensor is healthy/warming. 6 = the last one-minute reading was outside 39–501 mg/dL
         * (held for 10 minutes unless a valid reading clears it), 7 = replace (never set for a
         * Libre 3), 9 = the sensor's own patch state is past "active": expired, or stopped early.
         * Code 8 (not ready) is left to the warm-up note, not the error banner.
         */
        fun fromSensorState(state: Int): SensorError? = when (state) {
            6 -> SensorError(state, "Invalid reading", "The sensor's latest reading had no valid glucose value. That happens now and then and usually clears with the next reading, a minute later. Only if it goes on for hours is the sensor likely failing.")
            7 -> SensorError(state, "Replace sensor", "The sensor has failed and needs to be replaced.")
            9 -> SensorError(state, "Sensor stopped", "The sensor reports that it has stopped measuring, so no more readings will come from it. If its wear time wasn't over yet, it stopped early: replace it and report it to Abbott.")
            else -> null
        }

        /**
         * The error to show for a sensor, or null. A fault from its state (invalid reading /
         * replace / stopped) comes first; then a connection/handshake failure (no data is flowing
         * at all); then a reading at the edge of the measurable range.
         *
         * During the 60-minute warm-up the sensor sends placeholder readings, so the reading
         * derived codes (6 and [fromGlucoseAlarm]'s 4/5) mean nothing yet: the warm-up note
         * takes precedence. Only conclusive failures still show then: the sensor reporting that it
         * stopped (or a replace), and [connection], which holds nothing during warm-up but a
         * recorded handshake failure.
         *
         * @param state the engine's sensor state ([fromSensorState])
         * @param connection the current connection/handshake failure, or null
         * @param alarm the engine's per-reading alarm/state code ([fromGlucoseAlarm])
         * @param warmingUp whether the sensor is still in its warm-up
         */
        fun select(state: Int, connection: SensorError?, alarm: Int, warmingUp: Boolean): SensorError? {
            if (warmingUp) return fromSensorState(state)?.takeIf { it.code != STATE_INVALID_READING } ?: connection
            return fromSensorState(state) ?: connection ?: fromGlucoseAlarm(alarm)
        }

        /** Engine state for an out-of-range reading ([fromSensorState]). */
        private const val STATE_INVALID_READING = 6

        /**
         * Maps a BLE disconnect into a connection/handshake error, or null for a benign drop. The
         * repository shows it only once no reading has come for 6 minutes and the failure recurred
         * (or a handshake stage was recorded), and clears it with the next reading.
         *
         * Only a recorded [detail] proves a failed Libre 3 secure handshake (upstream issue #405:
         * Mogluco connects but the handshake never completes, so no data ever flows). Without one
         * the status only says how the link failed, so the text names that and no more.
         *
         * @param status the GATT status. 0 = success and 22 = local termination are benign on
         *   their own; 8 = link supervision timeout (an open link went silent), 19 = the sensor
         *   closed the link, 62 = the connection could not be established, 133 = Android's
         *   generic connection failure, 147 = connection attempt timed out
         *   (GATT_CONNECTION_TIMEOUT: the sensor never answered).
         * @param detail the failed handshake stage from the engine, or null. A non-null detail is
         *   a conclusive failure even when [status] is 0 (the app aborted the handshake locally,
         *   e.g. on an unexpected SEC_CHAR_CHALLENGE_DATA length).
         */
        fun fromConnection(status: Int, detail: String?): SensorError? {
            val benignStatus = status == 0 || status == 22
            if (benignStatus && detail == null) return null
            if (!detail.isNullOrBlank()) return handshakeFailed(status, detail)
            if (status in UNREACHABLE_STATUSES) return unreachable(status)
            val meaning = if (status == STATUS_PEER_TERMINATED) {
                "The sensor answered but closed the connection again before sending any data, " +
                    "several times in a row. Most often another device is using it (your watch, " +
                    "another phone or the Libre app); it is also how the Libre 3 secure handshake " +
                    "problem (issue #405) shows. Mogluco keeps retrying; make sure no other " +
                    "device is connected to this sensor."
            } else {
                "The connection to the sensor failed several times in a row before any data came " +
                    "through. Mogluco keeps retrying, which usually fixes it within minutes."
            }
            return SensorError(code = status, title = "Sensor keeps disconnecting", meaning = meaning, source = Source.BLUETOOTH)
        }

        /** GATT statuses for a link that never came up or went silent: 8, 62, 133, 147. */
        private val UNREACHABLE_STATUSES = setOf(8, 62, 133, 147)

        /** GATT status 19: the remote side (the sensor) terminated the connection. */
        private const val STATUS_PEER_TERMINATED = 19

        private fun unreachable(status: Int): SensorError {
            val observed = when (status) {
                8 -> "the Bluetooth link to the sensor keeps going silent"
                147 -> "the sensor doesn't answer connection attempts"
                62 -> "connections to the sensor can't be established"
                else -> "connection attempts to the sensor keep failing"
            }
            return SensorError(
                code = status,
                title = "No connection to sensor",
                meaning = "No reading for over 6 minutes: $observed. Mogluco keeps retrying by " +
                    "itself, and this usually ends once the phone is close to the sensor again. " +
                    "It also happens when another device (your watch, another phone or the " +
                    "Libre app) holds the connection, since the sensor talks to one device at a " +
                    "time. If there's no data for several hours, the sensor has likely stopped " +
                    "transmitting.",
                source = Source.BLUETOOTH,
            )
        }

        private fun handshakeFailed(status: Int, detail: String): SensorError {
            val how = when (status) {
                0, 22 -> "Mogluco aborted it"
                19 -> "the sensor closed the connection"
                else -> "Bluetooth status $status"
            }
            return SensorError(
                code = status,
                title = "Sensor setup failed",
                meaning = "Mogluco reached the sensor, but the Libre 3 secure handshake failed at " +
                    "stage $detail ($how), so no data can come through. Mogluco retries on the " +
                    "next connection. If it keeps failing, make sure no other device is connected " +
                    "to this sensor, then turn Bluetooth off and on. Failing on every sensor is " +
                    "the known Libre 3 handshake problem (issue #405).",
                source = Source.BLUETOOTH,
            )
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
