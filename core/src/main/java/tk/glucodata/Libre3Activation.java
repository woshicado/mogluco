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
/*      Headless FreeStyle Libre 3 NFC activation for the Compose redesign.            */
/*      Extracted from the legacy ScanNfcV.libre3scan() with all UI (GlucoseCurve,      */
/*      MainActivity, Vibrator, calendar, dialogs) removed — just the NFC handshake     */
/*      and the streamptr → outcome mapping.                                            */

package tk.glucodata;

import android.nfc.Tag;

/**
 * Activates (or takes over) a FreeStyle Libre 3 sensor over NFC. This runs the two-stage NFC
 * handshake ({@link Libre3#libre3NFC}) with the configured Libreview account ID and, on
 * success, hands the sensor to {@link SensorBluetooth} so BLE streaming begins.
 *
 * <p>Call from a background thread — {@link android.nfc.tech.NfcV} transceive is blocking.
 * Requires {@link Sensors#start()} to have run first so {@code SensorBluetooth.blueone} exists
 * (otherwise the sensor is registered but BLE won't start).
 */
public final class Libre3Activation {

    private static final String LOG_ID = "Libre3Activation";
    private static final long WARMUP_MS = 60L * 60_000L; // Libre 3 warm-up is 60 minutes

    public enum Outcome {
        /** Sensor accepted; BLE streaming is starting (may still be in warm-up). */
        ACTIVE,
        /** Sensor just activated / still warming up; no data pointer yet. */
        WARMING_UP,
        /** NFC read failed (tag moved, wrong tag, interference). */
        NFC_FAILED,
        /** Sensor has been terminated. */
        TERMINATED,
        /** Sensor has ended (past its life). */
        ENDED,
        /** The tapped tag is not a Libre 3 sensor. */
        NOT_LIBRE3,
        /** Activation failed for another reason (e.g. wrong/zero account ID). */
        ERROR,
    }

    public static final class Result {
        public final Outcome outcome;
        public final String serial;
        public final long warmupEndMillis;

        Result(Outcome outcome, String serial, long warmupEndMillis) {
            this.outcome = outcome;
            this.serial = serial;
            this.warmupEndMillis = warmupEndMillis;
        }
    }

    private Libre3Activation() {}

    /**
     * @param tag       the NFC tag from reader mode
     * @param accountId the Libreview account ID (0 to leave the engine's stored value)
     */
    public static Result activate(Tag tag, long accountId) {
        byte[] uid = tag.getId();
        if (uid == null || uid.length != 8 || uid[6] == 7) {
            Log.e(LOG_ID, "not a Libre 3 sensor");
            return new Result(Outcome.NOT_LIBRE3, null, 0L);
        }
        if (accountId > 0L) {
            Natives.setlibreAccountIDnumber(accountId);
        }

        long streamptr = Libre3.libre3NFC(tag);
        Log.i(LOG_ID, "libre3NFC streamptr=" + streamptr);

        if (streamptr == 2L) {
            return new Result(Outcome.NFC_FAILED, null, 0L);
        }
        if (streamptr >= 0L && streamptr < 7L) {
            switch ((int) (streamptr & 0xFFFFFFF)) {
                case 1:
                    return new Result(Outcome.WARMING_UP, null, System.currentTimeMillis() + WARMUP_MS);
                case 5:
                    return new Result(Outcome.TERMINATED, null, 0L);
                case 6:
                    return new Result(Outcome.ENDED, null, 0L);
                default:
                    return new Result(Outcome.ERROR, null, 0L);
            }
        }

        // streamptr is a real sensor pointer.
        String name = Natives.getSensorName(streamptr);
        if (name == null) {
            Log.e(LOG_ID, "getSensorName==null; freeing ptr");
            Natives.freedataptr(streamptr);
            return new Result(Outcome.ERROR, null, 0L);
        }
        Log.i(LOG_ID, "activated sensor " + name);
        // resetDeviceOrFree FREES the pointer (and does nothing) if the BLE manager isn't up,
        // so make sure it's started before the hand-off — otherwise we'd report ACTIVE for a
        // sensor that never gets a BLE connection.
        if (SensorBluetooth.blueone == null) {
            Log.i(LOG_ID, "BLE manager not started yet; starting before hand-off");
            Sensors.start();
        }
        // Hands off to BLE (starts streaming). Returns false if the BLE manager still isn't up —
        // in which case it FREES the pointer, so we must not report ACTIVE for a sensor that will
        // never stream.
        if (!SensorBluetooth.resetDeviceOrFree(streamptr, name)) {
            Log.e(LOG_ID, "resetDeviceOrFree failed (BLE unavailable); not streaming");
            return new Result(Outcome.ERROR, name, 0L);
        }

        long startMsec = 0L;
        try {
            startMsec = Natives.getSensorStartmsec(streamptr);
        } catch (Throwable ignore) {
            // best-effort; fall back to now + warm-up below
        }
        long warmupEnd =
            (startMsec > 0L) ? (startMsec + WARMUP_MS) : (System.currentTimeMillis() + WARMUP_MS);
        return new Result(Outcome.ACTIVE, name, warmupEnd);
    }
}
