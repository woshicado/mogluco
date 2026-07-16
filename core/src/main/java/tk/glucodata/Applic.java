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
/*      Engine callback bridge. The native engine resolves these static methods at    */
/*      load time (curve/javacurve.cpp JNI_OnLoad) and calls them at runtime.          */
/*      Also the minimal app-environment shim the ported Libre3 BLE/NFC classes        */
/*      (SuperGattCallback, SensorBluetooth, Libre3GattCallback) expect — decoupled    */
/*      from the legacy UI/notifications.                                              */

package tk.glucodata;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Static callbacks resolved by libg.so JNI_OnLoad (curve/javacurve.cpp), plus the
 *  app-environment statics the ported sensor classes reference. */
class Applic {
    private static final String LOG_ID = "Applic";

    static boolean Nativesloaded = false;

    // --- App environment expected by the ported legacy sensor classes ---

    /** Application context; set by {@link #init(Context)} from {@code Engine.start}. */
    public static Context app;

    /** mg/dL ↔ mmol/L conversion factor (legacy constant). */
    static final float mgdLmult = 18.0f;

    /** Device type from the :core flavor (mobile/wear); no built-in TTS on either. */
    static final boolean isWearable = BuildConfig.isWear == 1;
    static final boolean DontTalk = true;

    /** Wear Data Layer capability of the PEER app: each side looks up the other's
     *  (legacy Applic.java pattern — phone declares "juggluco", watch "jugglucowatch"). */
    static final String JUGGLUCOIDENT = isWearable ? "juggluco" : "jugglucowatch";

    /** Legacy parity: treat every peer as "galaxy" (selects the Data-Layer tunnel). */
    static final boolean ALLGALAXY = true;

    /** Watch side: set true once /start delivered the engine settings (bytesettings). */
    static volatile boolean initStarted = false;

    /** Hold a wake lock while connected (legacy default). */
    static final boolean usewakelock = true;

    /** Display unit code mirrored from the engine (0 = mg/dL, 1 = mmol/L). */
    public static int unit = 0;

    public static final Locale usedlocale = Locale.US;

    private static Handler mHandler;
    private static long uiThreadId;

    static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    /** Initializes the app-environment statics. Idempotent; call from {@code Engine.start}. */
    static synchronized void init(Context context) {
        if (app == null) {
            app = context.getApplicationContext();
        }
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
            uiThreadId = Looper.getMainLooper().getThread().getId();
        }
    }

    public static Handler getHandler() {
        return mHandler;
    }

    public static Context getContext() {
        return app;
    }

    public static void RunOnUiThread(Runnable action) {
        if (mHandler == null) {
            action.run();
        } else if (Thread.currentThread().getId() != uiThreadId) {
            mHandler.post(action);
        } else {
            action.run();
        }
    }

    public static void postDelayed(Runnable action, long mmsecs) {
        if (mHandler != null) {
            mHandler.postDelayed(action, mmsecs);
        }
    }

    // The legacy Toasters surfaced user-facing messages; in the redesign the UI owns all
    // user messaging, so these just log. Kept so ported classes compile unchanged.
    public static void Toaster(String mess) {
        Log.i(LOG_ID, "toast: " + mess);
    }

    public static void Toaster(int res) {
        Log.i(LOG_ID, "toast res=" + res);
    }

    /** No-op: the legacy OpenGL curve redraw has no equivalent in the Compose UI. */
    public static void updatescreen() {}

    private static final String[] scanpermissions =
        (Build.VERSION.SDK_INT >= 31)
            ? new String[] {
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_CONNECT",
            }
            : new String[] {
                "android.permission.ACCESS_FINE_LOCATION",
            };

    private static boolean hasScanPermissions() {
        Context context = app;
        if (context == null) {
            return false;
        }
        ArrayList<String> missing = new ArrayList<>();
        for (String p : scanpermissions) {
            if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        return missing.isEmpty();
    }

    static boolean mayscan() {
        return hasScanPermissions();
    }

    static boolean canBluetooth() {
        if (Build.VERSION.SDK_INT > 30) {
            return hasScanPermissions();
        }
        return true;
    }

    // --- Native engine callbacks (resolved by JNI_OnLoad) ---

    /**
     * The native engine's primary push: a new glucose reading. Forwarded to the app via
     * {@link Engine}. Argument order matches the JNI signature
     * {@code (Ljava/lang/String;IFFIJZJJI)V}.
     */
    static void doglucose(String serialNumber, int mgdl, float displayValue, float rate,
                          int alarm, long timeMillis, boolean wasBlueOff,
                          long sensorStartMillis, long sensorPtr, int sensorGen) {
        Engine.dispatchGlucose(serialNumber, mgdl, displayValue, rate, timeMillis, alarm);
    }

    static boolean updateDevices() {
        // The native engine calls this whenever the active-sensor set changes. On the WATCH this is
        // how a "Send sensor to watch" hand-off is finalized: the mirror makes this watch the sensor
        // owner, the sensor becomes active, and the engine calls here expecting us to build + connect
        // the BLE device. Left as a no-op stub, the transferred sensor never got a GATT connection,
        // so the watch never streamed and the phone's transfer reverted after the timeout ("watch
        // never confirmed"). This is the missing half of the direct sensor↔watch connection.
        //
        // Deliberately scoped to the watch: the phone's BLE device list is driven by explicit
        // Sensors.start()/reconnectAll() calls and already works — #1 priority is leaving the
        // phone↔sensor path untouched. The getusebluetooth() guard stops a watch in mirror mode
        // (phone still owns the sensor) from scanning for a sensor it must not hold.
        if (!isWearable) {
            return false;
        }
        if (!Natives.getusebluetooth()) {
            return false;
        }
        if (SensorBluetooth.blueone != null) {
            return SensorBluetooth.updateDevices();
        }
        // No BLE manager yet (updateDevices arrived before /bluetooth): start it, which builds the
        // device list from the now-active sensor set and connects.
        SensorBluetooth.start(true);
        return SensorBluetooth.blueone != null;
    }

    static boolean bluetoothEnabled() { return false; }

    static void speak(String message) {}

    // WEAROS-flavored libg.so resolves these two at load (javacurve.cpp #ifdef WEAROS):
    // legacy watch "initializing…" overlay text. The Compose watch UI has its own states.
    static void setinittext(String str) {
        Log.i(LOG_ID, "setinittext: " + str);
    }

    static void rminitlayout() {}

    // --- Phone ↔ watch mirror hooks (legacy Applic behavior, WatchLink-aware) ---

    /** Kick the mirror connections: wake the peer over the Data Layer + the native backup thread. */
    static void wakemirrors() {
        MessageSender.sendwake();
        Natives.wakebackup();
    }

    /** Native callback (JNI: resetWearOS()V): re-handshake the mirror after network changes. */
    static void resetWearOS() {
        MessageSender.reinit();
        wakemirrors();
    }

    /**
     * Turn this device's own sensor BLE on/off (legacy Applic.setbluetooth). Off is what
     * happens when the peer takes the sensor over; on re-establishes our connection.
     */
    public static void setbluetooth(boolean on) {
        if (Log.doLog) Log.i(LOG_ID, "setbluetooth(" + on + ")");
        Natives.setusebluetooth(on);
        if (on) {
            try {
                Sensors.start();
                Sensors.reconnectAll();
            } catch (Throwable t) {
                Log.stack(LOG_ID, "setbluetooth start", t);
            }
        } else {
            try {
                Sensors.stop();
            } catch (Throwable t) {
                Log.stack(LOG_ID, "setbluetooth stop", t);
            }
        }
        WatchLink.onBluetoothChanged(on);
    }

    static void toGarmin(int v) {}

    static void Garmindeletelast(int a, int b, int c) {}

    /**
     * Native callback (JNI: switchbluetooth(Ljava/lang/String;[BZ)Z) — the transfer ACK
     * entry: the native side calls this when the peer announced sensor ownership over the
     * mirror (sBlueWatch). Phone-only; body is the legacy sequence.
     */
    static boolean switchbluetooth(String name, byte[] netinfo, boolean watchBluetooth) {
        if (!isWearable) {
            if (netinfo != null) {
                MessageSender sender = MessageSender.getMessageSender();
                if (sender != null) {
                    sender.sendnetinfo(name, netinfo);
                    sender.sendbluetooth(name, watchBluetooth);
                    setbluetooth(!watchBluetooth);
                    WatchLink.onNativeSwitch(name, watchBluetooth);
                    return true;
                }
            }
        }
        return false;
    }

    static int bluePermission() { return 0; }
}
