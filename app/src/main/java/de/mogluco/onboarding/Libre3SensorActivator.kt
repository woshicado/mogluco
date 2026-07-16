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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.Libre3Activation

/**
 * Real Libre 3 activation, backed by the native engine in `:core`. Runs the two-stage NFC
 * handshake (with the Libreview account ID the user entered) and hands the sensor off to BLE
 * streaming. NFC transceive is blocking, so it runs on [Dispatchers.IO].
 *
 * This is the device-in-the-loop path: it can only succeed against a physical Libre 3 sensor.
 *
 * @param accountIdProvider supplies the latest Libreview account ID (0 if none set).
 */
class Libre3SensorActivator(
    private val accountIdProvider: () -> Long,
) : SensorActivator {

    override suspend fun activate(tag: Tag): ActivationResult = withContext(Dispatchers.IO) {
        val result = Libre3Activation.activate(tag, accountIdProvider())
        when (result.outcome) {
            Libre3Activation.Outcome.ACTIVE ->
                if (result.warmupEndMillis <= System.currentTimeMillis()) {
                    ActivationResult.Active
                } else {
                    ActivationResult.WarmingUp(result.warmupEndMillis)
                }
            Libre3Activation.Outcome.WARMING_UP ->
                ActivationResult.WarmingUp(result.warmupEndMillis)
            Libre3Activation.Outcome.NFC_FAILED ->
                // A flaky NFC read can still coincide with a successful take-over/BLE start.
                ActivationResult.Failed(
                    "Couldn't read the sensor — hold your phone steady against it and try again.",
                    transient = true,
                )
            Libre3Activation.Outcome.TERMINATED ->
                ActivationResult.Failed("This sensor has been terminated and can't be used.")
            Libre3Activation.Outcome.ENDED ->
                ActivationResult.Failed("This sensor has ended.")
            Libre3Activation.Outcome.NOT_LIBRE3 ->
                ActivationResult.Failed("That doesn't look like a Libre 3 sensor.")
            Libre3Activation.Outcome.ERROR ->
                // Often a rescan/take-over of an already-active sensor: the NFC step errors but the
                // engine registers it and BLE streams a moment later — so let the UI confirm via a
                // fresh reading before failing.
                ActivationResult.Failed(
                    "Activation failed. Check your account ID and try scanning again.",
                    transient = true,
                )
        }
    }
}
