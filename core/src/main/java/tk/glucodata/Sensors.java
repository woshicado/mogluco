/*      This file is part of Mogluco, a Material 3 redesign fork of                  */
/*      Juggluco, for Freestyle Libre 2 and 3 glucose sensors.                       */
/*                                                                                   */
/*      Copyright (C) 2026 Joshua Meyer                                              */
/*      Portions derived from Juggluco, Copyright (C) 2021                           */
/*      Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>.                           */
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
/*      Clean facade over the ported Libre 3 BLE orchestration (SensorBluetooth) for     */
/*      the Compose redesign — the app talks to this, never to SensorBluetooth or        */
/*      Natives directly. Parallel to {@link Engine} (which owns the data path).         */

package tk.glucodata;

/**
 * Sensor connection lifecycle for the redesigned app: start/stop background BLE scanning,
 * manual rescan/reconnect, deactivation, and a connection-state callback. Glucose readings
 * themselves flow through {@link Engine.Listener} (via {@code Applic.doglucose}), not here.
 */
public final class Sensors {

    private static final String LOG_ID = "Sensors";

    /** Connection state for the active sensor(s), delivered on the BLE callback thread. */
    public interface StateListener {
        /**
         * @param serial    the sensor's serial number.
         * @param connected true on connect, false on disconnect.
         * @param status    the BLE GATT status. 0 (and 22 = local termination) are benign; a
         *                  non-zero value on a disconnect signals a connection/handshake failure
         *                  (e.g. 19 = sensor dropped the link, 133 = connection failed, 147 =
         *                  secure handshake rejected).
         * @param detail    a short description of the last handshake stage when it failed, or null
         *                  for a normal disconnect. Non-null is a conclusive failure signal even
         *                  when {@code status} is 0 (the app aborted the handshake locally).
         */
        void onConnectionChanged(String serial, boolean connected, int status, String detail);
    }

    private static volatile StateListener listener;

    public static void setStateListener(StateListener l) {
        listener = l;
    }

    /** Result of a manual "Pull history from sensor" (deep replay), delivered on the BLE thread. */
    public interface DeepPullListener {
        /** @param serial the sensor; @param records how many historic records it replayed. */
        void onDeepPullComplete(String serial, int records);
    }

    private static volatile DeepPullListener deepPullListener;

    public static void setDeepPullListener(DeepPullListener l) {
        deepPullListener = l;
    }

    /** Invoked by the Libre 3 GATT callback when a deep history replay finishes. */
    static void notifyDeepPull(String serial, int records) {
        DeepPullListener l = deepPullListener;
        if (l != null) {
            try {
                l.onDeepPullComplete(serial, records);
            } catch (Throwable t) {
                Log.stack(LOG_ID, "notifyDeepPull", t);
            }
        }
    }

    /** Invoked by the ported GATT callbacks on connect/disconnect. */
    static void notifyConnection(String serial, boolean connected, int status, String detail) {
        StateListener l = listener;
        if (l != null) {
            try {
                l.onConnectionChanged(serial, connected, status, detail);
            } catch (Throwable t) {
                Log.stack(LOG_ID, "notifyConnection", t);
            }
        }
    }

    private Sensors() {}

    /**
     * Begins background scanning/connecting for all activated sensors. Idempotent. Must be
     * called <b>after</b> {@code Engine.start()} so the native engine is booted.
     *
     * <p>Gated on the persisted ownership flag: when this device handed the sensor to its peer
     * (phone→watch transfer, or a watch in mirror mode), {@code usebluetooth} is off and this
     * device must NOT scan. A hardcoded {@code true} here meant every app restart silently
     * re-armed scanning behind the role state's back — the device then competed for the sensor's
     * single BLE slot and, the moment the owner dropped off (watch battery died), grabbed the
     * connection while the UI still showed the peer as owner. {@code Applic.setbluetooth(true)}
     * is the path that re-enables scanning, and it flips the role state with it.
     */
    public static void start() {
        SuperGattCallback.autoconnect = Natives.getAndroid13();
        SensorBluetooth.start(Natives.getusebluetooth());
    }

    /** Kick a scan for the active sensor's advertisement. */
    public static void startScan() {
        SensorBluetooth.startscan();
    }

    /** Re-attempt connection for all known sensors (e.g. after a dropped link). */
    public static void reconnectAll() {
        SensorBluetooth.reconnectall();
    }

    /**
     * Reconnect watchdog for a device that owns the sensor's BLE link but has stopped receiving
     * readings. Upstream re-armed a loss-of-signal alarm whose handler began with
     * {@code SensorBluetooth.reconnectall()}; the redesign dropped that alarm, so a link that
     * stalled without a clean disconnect (failed service discovery, a handshake that never
     * completes, a null connectGatt) was never retried. No-op unless this device owns the link
     * ({@code usebluetooth}); rebuilds the BLE manager if it was torn down. Each sensor's
     * {@link SuperGattCallback#reconnect} only acts when its own data is stale, so a kick on a
     * healthy link does nothing.
     */
    public static void watchdogKick() {
        if (!Natives.getusebluetooth()) {
            return;
        }
        if (SensorBluetooth.blueone == null) {
            Log.i(LOG_ID, "watchdog: no BLE manager while owning the sensor — starting it");
            SensorBluetooth.start(true);
        } else {
            Log.i(LOG_ID, "watchdog: readings stale — reconnecting");
            SensorBluetooth.reconnectall();
        }
    }

    /** Connect immediately to the active device (used after a manual NFC re-tap). */
    public static void goScan() {
        SensorBluetooth.goscan();
    }

    public static boolean updateDevices() {
        return SensorBluetooth.updateDevices();
    }

    /** The serial numbers of currently-activated sensors. */
    public static String[] activeSensors() {
        return Natives.activeSensors();
    }

    /** Activation (warm-up start) time in epoch millis for the given serial, or 0 if unknown. */
    public static long sensorStartMillis(String serial) {
        if (serial == null) {
            return 0L;
        }
        long dataptr = Natives.getdataptr(serial);
        return dataptr != 0L ? Natives.getSensorStartmsec(dataptr) : 0L;
    }

    /**
     * Current sensor state for the given serial: -1 invalid, 0 ok, 6 sensor error (defective),
     * 7 replace sensor, 8 not ready (warm-up), 9 ended.
     */
    public static int sensorState(String serial) {
        if (serial == null) {
            return -1;
        }
        long dataptr = Natives.getdataptr(serial);
        return dataptr != 0L ? Natives.getSensorState(dataptr) : -1;
    }

    /**
     * Deactivates the sensor with the given serial: tells the native engine to finish it and
     * tears down its BLE connection.
     */
    public static void deactivate(String serial) {
        if (serial == null) {
            return;
        }
        long dataptr = Natives.getdataptr(serial);
        if (dataptr != 0L) {
            Natives.finishSensor(dataptr);
        }
        SensorBluetooth.sensorEnded(serial);
        Log.i(LOG_ID, "deactivated " + serial);
    }

    /** Tears down all BLE connections (e.g. on shutdown). */
    public static void stop() {
        SensorBluetooth.destructor();
    }

    /**
     * Manual "Pull history from sensor": force the connected Libre 3 to replay its on-sensor historic
     * buffer to fill interior gaps that the automatic high-water-mark backfill skips (it only pulls
     * forward from the newest record it holds). {@code minutes} is how far back to replay; {@code <= 0}
     * pulls the whole session. The sensor keeps ~14 days on-device, so recent gaps are recoverable.
     * Returns the number of connected sensors asked (0 = none — nothing to pull from).
     */
    public static int pullHistoryFromSensor(int minutes) {
        return SensorBluetooth.pullHistoryFromSensor(minutes, true);
    }

    /**
     * As {@link #pullHistoryFromSensor(int)} but [announce]=false keeps it silent (no result toast) —
     * used by the automatic gap-fill on sensor reconnect, where the user shouldn't be bothered.
     */
    public static int pullHistoryFromSensor(int minutes, boolean announce) {
        return SensorBluetooth.pullHistoryFromSensor(minutes, announce);
    }
}
