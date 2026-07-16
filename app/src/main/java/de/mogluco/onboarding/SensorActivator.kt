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
package de.mogluco.onboarding

import android.nfc.Tag
import kotlinx.coroutines.delay

/** Outcome of tapping a sensor during onboarding. */
sealed interface ActivationResult {
    /** Activation succeeded; sensor is warming up until [warmupEndMillis]. */
    data class WarmingUp(val warmupEndMillis: Long) : ActivationResult

    /** Sensor is already active and streaming. */
    data object Active : ActivationResult

    /**
     * Activation reported a failure. [transient] marks the ambiguous handshake failures (a flaky NFC
     * read, or a take-over/rescan where the NFC step errors but the engine still registers the sensor
     * and BLE streaming starts a moment later) — for those the UI waits briefly for a reading before
     * calling it a failure. Definitive failures (terminated / ended / not a Libre 3) set it false.
     */
    data class Failed(val reason: String, val transient: Boolean = false) : ActivationResult
}

/**
 * The seam between the onboarding UI and the actual Libre3 activation. The real
 * implementation runs the legacy NFC command sequence (AlgNfcV / Natives.interpret3NFC2 /
 * startTimeIDsum) and hands off to BLE pairing (Libre3GattCallback) — that path can only be
 * exercised and validated with a physical sensor, so it is wired with the device in the loop.
 */
interface SensorActivator {
    suspend fun activate(tag: Tag): ActivationResult
}

/**
 * Emulator/dev stand-in so the onboarding UX (permissions → scan → activating → warm-up →
 * done) can be walked through and verified without a sensor. Replaced by the real
 * device-backed activator. NEVER ships as the default on release.
 */
class SimulatedSensorActivator : SensorActivator {
    override suspend fun activate(tag: Tag): ActivationResult {
        delay(2_500) // mimic the NFC command exchange
        return ActivationResult.WarmingUp(
            warmupEndMillis = System.currentTimeMillis() + 60 * 60_000L,
        )
    }
}
