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
/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*      Licensed under the GNU General Public License v3 or later.                   */

package tk.glucodata;

import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Clean entry point to the native engine for the redesigned UI.
 *
 * <p>Wraps the bootstrap sequence that the legacy {@code tk.glucodata.nums.numio} performs
 * (setlocale → setfilesdir → startsensors/startmeals/startthreads) and turns the engine's
 * push callback ({@link Applic#doglucose}) into a single {@link Listener} the app registers.
 *
 * <p>This is the seam between the native engine and the Compose app: the app never touches
 * {@code Applic} or {@code Natives} directly, only this facade and {@code GlucoseRepository}.
 */
public final class Engine {

    private static final String LOG_ID = "Engine";

    /** Callback for engine events delivered on the engine's own thread. */
    public interface Listener {
        /**
         * A new glucose reading arrived.
         *
         * @param serial      sensor serial number
         * @param mgdl        glucose in mg/dL
         * @param displayValue glucose in the engine's configured display unit
         * @param rate        rate of change (mg/dL per minute)
         * @param timeMillis  measurement timestamp
         * @param alarm       engine alarm code (0 = none)
         */
        void onGlucose(String serial, int mgdl, float displayValue, float rate,
                       long timeMillis, int alarm);
    }

    private static volatile Listener listener;
    private static volatile boolean started;

    /** Nums store pointers: [0] = persisted ("watch"), [1] = live ("here"). */
    private static long numsLive;
    private static long numsStore;

    private Engine() {}

    public static void setListener(Listener l) {
        listener = l;
    }

    /** Invoked by {@link Applic#doglucose} when the native engine pushes a reading. */
    static void dispatchGlucose(String serial, int mgdl, float displayValue, float rate,
                                long timeMillis, int alarm) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onGlucose(serial, mgdl, displayValue, rate, timeMillis, alarm);
            } catch (Throwable t) {
                Log.e(LOG_ID, "listener.onGlucose threw", t);
            }
        }
    }

    // --- Bulk-data invalidation (phone↔watch mirror) ---
    // Fired when the store changed underneath the repositories without per-reading
    // callbacks: a mirror (re)sync, /start settings bootstrap, or a network reset.
    // Multiple consumers (history reload, watch settings sync) can register.

    private static final java.util.concurrent.CopyOnWriteArrayList<Runnable> invalidateListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addInvalidateListener(Runnable l) {
        if (l != null) {
            invalidateListeners.addIfAbsent(l);
        }
    }

    static void dispatchInvalidate() {
        for (Runnable l : invalidateListeners) {
            try {
                l.run();
            } catch (Throwable t) {
                Log.e(LOG_ID, "invalidateListener threw", t);
            }
        }
    }

    /**
     * Boots the native engine. Idempotent. Safe to call from {@code Application.onCreate}.
     * Must run after {@link Natives} has loaded libg.so (its static initializer).
     */
    public static synchronized void start(Context context) {
        if (started) {
            return;
        }
        Context app = context.getApplicationContext();
        String filesDir = app.getFilesDir().getAbsolutePath();
        String nativeDir = app.getApplicationInfo().nativeLibraryDir;
        Locale locale = Locale.getDefault();
        String lang = locale.getLanguage();
        String country = locale.getCountry();

        // Set up the app-environment statics the ported sensor classes rely on before any
        // native call, so logging/handlers are live during bootstrap.
        Applic.init(app);

        try {
            Natives.setlocale(lang);
            int ret = Natives.setfilesdir(filesDir, country, nativeDir);
            Log.i(LOG_ID, "setfilesdir(" + filesDir + ") ret=" + ret);
            Natives.startsensors();
            Natives.startmeals();
            Natives.startthreads();
            // Open the nums stores (order matters — mirrors nums.numio).
            numsLive = Natives.openNums("here", 0L);
            Natives.newhit();
            numsStore = Natives.openNums("watch", -1L);
            Applic.unit = Natives.getunit();
            Applic.Nativesloaded = true;
            started = true;
            Log.i(LOG_ID, "engine started; labels=" + getLabels());
            Log.i(LOG_ID, "settings unit=" + Natives.getunit()
                + " target=[" + Natives.targetlow() + "," + Natives.targethigh() + "]"
                + " " + alarmSettings());
        } catch (Throwable t) {
            Log.e(LOG_ID, "engine start failed", t);
        }
    }

    public static boolean isStarted() {
        return started;
    }

    // --- Events (carbs / insulin / notes) ---

    /** Event-type labels; the list index is the {@code type} passed to {@link #logEvent}. */
    public static List<String> getLabels() {
        try {
            ArrayList<String> labels = Natives.getLabels();
            return labels != null ? labels : new ArrayList<>();
        } catch (Throwable t) {
            Log.e(LOG_ID, "getLabels failed", t);
            return new ArrayList<>();
        }
    }

    /** The label index representing carbohydrates / meals. */
    public static int carbsType() {
        return Natives.getmealvar();
    }

    /**
     * The label index representing a fingerstick blood-glucose reference. Logging an event with
     * this index is equivalent to {@link #addCalibration} (both write {@code getbloodvar()}).
     */
    public static int bloodType() {
        return Natives.getbloodvar() & 0xFF;
    }

    /**
     * Logs an event (carbs in grams, insulin in units, …) at {@code timeMillis}.
     * @param type a label index from {@link #getLabels()}
     */
    public static void logEvent(int type, float value, long timeMillis) {
        if (!started) {
            Log.e(LOG_ID, "logEvent before engine start");
            return;
        }
        Natives.saveNum(numsLive, timeMillis / 1000L, value, type, 0);
        Log.i(LOG_ID, "logEvent type=" + type + " value=" + value);
    }

    /** Number of stored events (for verification / read-back). */
    public static int eventCount() {
        return started ? Natives.getlastNum(numsLive) : 0;
    }

    /**
     * Deletes the first stored event matching {@code timeMillis}/{@code type}/{@code value}.
     * Events carry no stable id, so they're matched by their (time, label, value) tuple.
     * @return true if an event was removed.
     */
    public static boolean deleteEvent(long timeMillis, int type, float value) {
        if (!started) {
            return false;
        }
        long timeSec = timeMillis / 1000L;
        int n = Natives.getlastNum(numsLive);
        for (int pos = 0; pos < n; pos++) {
            tk.glucodata.nums.item it = Natives.getNumitem(numsLive, pos);
            if (it != null && it.time == timeSec && it.type == type
                && Math.abs(it.value - value) < 1e-3f) {
                Natives.removeNum(numsLive, pos);
                Log.i(LOG_ID, "deleteEvent pos=" + pos + " type=" + type);
                return true;
            }
        }
        return false;
    }

    /** Deletes all stored events strictly before {@code millis}. Returns the count removed. */
    public static int deleteEventsBefore(long millis) {
        if (!started) {
            return 0;
        }
        long cutoffSec = millis / 1000L;
        int removed = 0;
        // removeNum tombstones the slot in place (numdata.hpp numremove sets removedbit; nothing
        // shifts) and getNumitem returns null for tombstoned slots, so positions are stable: one
        // forward pass that skips nulls removes every event below the cutoff.
        int n = Natives.getlastNum(numsLive);
        for (int pos = 0; pos < n; pos++) {
            tk.glucodata.nums.item it = Natives.getNumitem(numsLive, pos);
            if (it != null && it.time < cutoffSec) {
                Natives.removeNum(numsLive, pos);
                removed++;
            }
        }
        Log.i(LOG_ID, "deleteEventsBefore removed=" + removed);
        return removed;
    }

    // --- LibreView (fetch account ID + upload) ---

    /** Store the LibreView credentials/region used for the account-ID fetch and uploads. */
    public static void setLibreviewCredentials(String email, String password, int country) {
        Natives.setlibreemail(email == null ? "" : email);
        Natives.setlibrepass(password == null ? "" : password);
        Natives.setLibreCountry(country);
    }

    public static String libreviewEmail() {
        return started ? safe(Natives.getlibreemail()) : "";
    }

    public static int libreviewCountry() {
        return started ? Natives.getLibreCountry() : 0;
    }

    /**
     * Triggers an asynchronous LibreView account-ID fetch (the native thread contacts the server
     * using the stored email/password). Poll {@link #libreAccountId()} / {@link #libreviewStatus()}
     * for the result. Mirrors the legacy "Get Account ID → From Libreview".
     */
    public static void fetchAccountId() {
        if (!started) {
            return;
        }
        Natives.setlibreAccountIDnumber(-1L); // legacy sentinel: derive number from the fetched string
        Natives.askServerforAccountID();
    }

    /** The fetched/stored LibreView account ID string (empty until fetched or set). */
    public static String libreAccountId() {
        return started ? safe(Natives.getlibreAccountID()) : "";
    }

    /** The numeric account ID sent to the sensor during activation. */
    public static long libreAccountIdNumber() {
        return started ? Natives.getlibreAccountIDnumber() : 0L;
    }

    /** Human-readable status of the last LibreView operation. */
    public static String libreviewStatus() {
        return Libreview.librestatus;
    }

    /**
     * This install's LibreView device identifier (the "uniqueIdentifier" sent with uploads).
     * LibreView's own CSV export lists it in the Serial Number column for phone-app uploads.
     */
    public static String libreviewDeviceId() {
        return started ? safe(Natives.getlibreDeviceID(true)) : "";
    }

    /** Patient name ("First Last") stored from the LibreView account fetch, or "". */
    public static String libreviewPatientName() {
        return started ? safe(Natives.getPatientName()) : "";
    }

    /** Timestamp string of the last successful upload, or null. */
    public static String libreviewLastUpload() {
        return Libreview.posttime;
    }

    public static boolean isUploadEnabled() {
        return started && Natives.getuselibreview();
    }

    // Bumped by every explicit upload-toggle (the persistent "Upload automatically" switch, pushNow).
    // pushOnce()'s delayed revert captures this and only fires if it hasn't changed since — so a
    // persistent enable made inside the 30s one-shot window cancels the pending auto-off.
    private static volatile int uploadIntentGen = 0;

    /** Enable/disable uploading collected data to LibreView. */
    public static void setUploadEnabled(boolean on) {
        if (started) {
            uploadIntentGen++;
            Natives.setuselibreview(on);
            Log.i(LOG_ID, "setUploadEnabled " + on);
        }
    }

    /** Wake the upload thread to send now (only uploads if uploading is enabled). */
    public static void uploadNow() {
        if (started) {
            Natives.wakelibreview(0);
        }
    }

    /**
     * One-tap push to LibreView. The native upload thread only POSTs when uploading is enabled,
     * so this enables it and wakes the thread immediately. Uploading stays on afterwards (the
     * native design ties on-demand and background upload together).
     */
    public static void pushNow() {
        if (started) {
            uploadIntentGen++;
            Natives.setuselibreview(true);
            Natives.wakelibreview(0);
            Log.i(LOG_ID, "pushNow");
        }
    }

    /**
     * One-shot push that does NOT leave background uploading on. The native upload thread only POSTs
     * while uploading is enabled, so we briefly enable it and wake the thread; if the user hadn't
     * turned background upload on, restore it to off after the post window. The persistent "Upload
     * automatically" toggle is left untouched.
     */
    public static void pushOnce() {
        if (!started) {
            return;
        }
        boolean wasOn = Natives.getuselibreview();
        Natives.setuselibreview(true);
        Natives.wakelibreview(0);
        if (!wasOn) {
            final int gen = uploadIntentGen;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                // Only revert if the user hasn't touched the upload toggle since (which bumps
                // uploadIntentGen) — otherwise a persistent "Upload automatically" enabled inside
                // this 30s window would be silently turned back off.
                if (started && gen == uploadIntentGen) {
                    Natives.setuselibreview(false);
                }
            }, 30_000L);
        }
        Log.i(LOG_ID, "pushOnce (wasOn=" + wasOn + ")");
    }

    // --- Logged-event (insulin/carb) upload ---
    //
    // The native upload thread can embed logged amounts in the same LibreView POST as the glucose
    // readings, but only for labels classified in its librenums table and only while its global
    // sendnumbers switch is on. Neither has any other setter in this app, so both are driven from
    // here (see de.mogluco.data.LibreEventSync).

    /** Include logged events (insulin/carbs) in LibreView uploads. No-op until classified via
     *  {@link #classifyEventLabel}; data still only leaves while uploading is enabled. */
    public static void setEventUpload(boolean on) {
        if (started) {
            Natives.setSendNumbers(on);
            Log.i(LOG_ID, "setEventUpload " + on);
        }
    }

    /** Whether logged events are included in LibreView uploads. */
    public static boolean isEventUpload() {
        return started && Natives.getSendNumbers();
    }

    /**
     * Classify an event label for LibreView upload.
     *
     * @param index a label index from {@link #getLabels()}
     * @param kind 0=don't upload, 1=rapid insulin, 2=long insulin, 3=carbs, 4=custom note
     * @param gramsPerUnit for kind 3, grams per logged unit (1.0 when values are already grams);
     *   ignored for the other kinds
     */
    public static void classifyEventLabel(int index, int kind, float gramsPerUnit) {
        if (started) {
            Natives.setlibrenum(0, index, kind, gramsPerUnit);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // --- Calibration ---
    //
    // Libre 3 is factory-calibrated. "Apply manual calibration" means: adjust displayed values
    // using fingerstick reference points the user enters. When OFF, the sensor's factory values
    // are shown as-is. So the redesign's "Automatic calibration" (trust the sensor) maps to
    // setApplyCalibration(false), and turning it off enables manual fingerstick calibration.

    /** Whether user manual-calibration corrections are currently applied. */
    public static boolean isApplyCalibration() {
        return started && Natives.getDoCalibrate();
    }

    /** Enable/disable applying user manual-calibration corrections. */
    public static void setApplyCalibration(boolean apply) {
        if (!started) {
            return;
        }
        Natives.setDoCalibrate(apply);
        Natives.setshowcalibrated(apply);
        Log.i(LOG_ID, "setApplyCalibration " + apply);
    }

    /**
     * Records a fingerstick blood-glucose reference (in the engine's display unit) at the current
     * time. The native engine recomputes the calibration coefficients from it. Requires
     * {@link #setApplyCalibration}(true) for the correction to take effect.
     */
    public static void addCalibration(float referenceValue) {
        if (!started) {
            Log.e(LOG_ID, "addCalibration before engine start");
            return;
        }
        Natives.saveNum(numsLive, System.currentTimeMillis() / 1000L, referenceValue, Natives.getbloodvar(), 0);
        Log.i(LOG_ID, "addCalibration value=" + referenceValue);
    }

    // --- Stored glucose history ---

    /** Hard cap on points read per sensor (≈ 14 days of 5-minute values + slack). */
    private static final int MAX_HISTORY_POINTS = 8192;

    /**
     * Reads the full stored 5-minute glucose history for all stored sensors (past + present), as a flat array of
     * {@code [timeMillis, mgdl, timeMillis, mgdl, …]} ordered by time. Empty if the engine isn't
     * started or no sensor has data. This is what backfills the chart/calendar/export with the
     * sensor's pre-app-start readings (the engine stores them via saveLibre3History/saveLibre3MinuteL).
     */
    // synchronized: allSensorPtrs()/getSensorData() lazily maps sensors and grows the native hist[]
    // table, so it is not reentrant. Two callers reach here off the main thread —
    // NativeGlucoseRepository.loadHistory (Dispatchers.Default) and WatchLink.buildCatchupPayload
    // (the Data-Layer /catchup callback thread) — and can fire together (a mirror-sync invalidate
    // burst while a peer reappears). Serializing on Engine.class keeps the native enumeration
    // single-threaded so the two readers can't corrupt the table / SIGSEGV.
    public static synchronized long[] glucoseHistory() {
        if (!started) {
            return new long[0];
        }
        // The live poll (per-reading "current" value) stream is authoritative and complete while
        // connected. The historic array is a PARALLEL per-minute stream (the algorithm-confirmed
        // value, ~16 min delayed) plus any recovered on-sensor buffer — so adding all of it would
        // duplicate nearly every reading (two points per minute). Collect the two streams
        // separately and only use a historic point where the poll stream has no reading nearby (a
        // real gap), so historic fills gaps without duplicating the live stream.
        java.util.ArrayList<long[]> poll = new java.util.ArrayList<>();
        java.util.ArrayList<long[]> historic = new java.util.ArrayList<>();
        try {
            // All stored sensors (past + present), so the chart/calendar show the full history,
            // not just sensors active in the last day. Read-only enumeration.
            long[] sensors = Natives.allSensorPtrs();
            if (sensors == null) {
                return new long[0];
            }
            for (long sensorptr : sensors) {
                if (sensorptr == 0L) {
                    continue;
                }
                int pos = 0;
                for (int guard = 0; guard < MAX_HISTORY_POINTS; guard++) {
                    long packed = Natives.streamfromSensorptr(sensorptr, pos);
                    long timeSec = packed & 0xFFFFFFFFL;
                    int mgdl = (int) ((packed >> 32) & 0xFFFF);
                    int nextPos = (int) ((packed >> 48) & 0xFFFF);
                    if (timeSec == 0L) {
                        break; // end-of-stream sentinel
                    }
                    poll.add(new long[] {timeSec * 1000L, mgdl});
                    if (nextPos <= pos) {
                        break; // no progress — avoid an infinite loop
                    }
                    pos = nextPos;
                }
                int hpos = 0;
                for (int guard = 0; guard < MAX_HISTORY_POINTS; guard++) {
                    long packed = Natives.historyfromSensorptr(sensorptr, hpos);
                    long timeSec = packed & 0xFFFFFFFFL;
                    int mgdl = (int) ((packed >> 32) & 0xFFFF);
                    int nextPos = (int) ((packed >> 48) & 0xFFFF);
                    if (timeSec == 0L) {
                        break;
                    }
                    historic.add(new long[] {timeSec * 1000L, mgdl});
                    if (nextPos <= hpos) {
                        break;
                    }
                    hpos = nextPos;
                }
            }
        } catch (Throwable t) {
            Log.e(LOG_ID, "glucoseHistory failed", t);
        }
        // Keep all poll points; add a historic point only when the nearest poll reading is more than
        // HIST_GAP_FILL_MS away (i.e. it sits in a genuine gap the live stream never covered).
        poll.sort((a, b) -> Long.compare(a[0], b[0]));
        long[] pollTimes = new long[poll.size()];
        for (int i = 0; i < poll.size(); i++) {
            pollTimes[i] = poll.get(i)[0];
        }
        java.util.ArrayList<long[]> points = poll; // reuse the poll list as the merged result
        for (long[] h : historic) {
            if (nearestDistance(pollTimes, h[0]) > HIST_GAP_FILL_MS) {
                points.add(h);
            }
        }
        points.sort((a, b) -> Long.compare(a[0], b[0]));
        long[] out = new long[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            out[i * 2] = points.get(i)[0];
            out[i * 2 + 1] = points.get(i)[1];
        }
        return out;
    }

    /** Distance (ms) from {@code t} to the nearest value in the sorted {@code times}, or MAX if empty. */
    private static long nearestDistance(long[] times, long t) {
        if (times.length == 0) {
            return Long.MAX_VALUE;
        }
        int idx = java.util.Arrays.binarySearch(times, t);
        if (idx >= 0) {
            return 0L;
        }
        idx = -idx - 1; // insertion point
        long best = Long.MAX_VALUE;
        if (idx < times.length) {
            best = Math.abs(times[idx] - t);
        }
        if (idx > 0) {
            best = Math.min(best, Math.abs(t - times[idx - 1]));
        }
        return best;
    }

    /** A historic point is only added when the nearest live reading is at least this far away — long
     *  enough that the parallel per-minute historic stream never duplicates the live stream, short
     *  enough that a real gap still gets filled. */
    private static final long HIST_GAP_FILL_MS = 3 * 60 * 1000L;

    // --- Units / target range / alarms ---

    /** Raw engine unit code. */
    public static int unitCode() {
        return Natives.getunit();
    }

    /** Whether the engine is configured to display mmol/L (code 1) vs mg/dL. */
    public static boolean unitIsMmol() {
        return Natives.getunit() == 1;
    }

    public static float targetLow() {
        return Natives.targetlow();
    }

    public static float targetHigh() {
        return Natives.targethigh();
    }

    public static AlarmSettings alarmSettings() {
        return new AlarmSettings(
            Natives.alarmlow(),
            Natives.alarmhigh(),
            Natives.hasalarmlow(),
            Natives.hasalarmhigh(),
            Natives.hasvaluealarm(),
            Natives.hasalarmloss());
    }

    public static void setAlarmSettings(AlarmSettings s) {
        Natives.setalarms(s.low, s.high, s.lowEnabled, s.highEnabled, s.availableEnabled, s.lossEnabled);
        Log.i(LOG_ID, "setAlarmSettings " + s);
    }

    /** Immutable snapshot of the alarm configuration. */
    public static final class AlarmSettings {
        public final float low;
        public final float high;
        public final boolean lowEnabled;
        public final boolean highEnabled;
        public final boolean availableEnabled;
        public final boolean lossEnabled;

        public AlarmSettings(float low, float high, boolean lowEnabled, boolean highEnabled,
                             boolean availableEnabled, boolean lossEnabled) {
            this.low = low;
            this.high = high;
            this.lowEnabled = lowEnabled;
            this.highEnabled = highEnabled;
            this.availableEnabled = availableEnabled;
            this.lossEnabled = lossEnabled;
        }

        @Override public String toString() {
            return "Alarms{low=" + low + ",high=" + high + ",lowEn=" + lowEnabled
                + ",highEn=" + highEnabled + ",availEn=" + availableEnabled + ",lossEn=" + lossEnabled + "}";
        }
    }

    /**
     * Writes an export to the given file descriptor.
     * @param type 0=events, 1=scans, 2=stream, 3=glucose history, 4=meals, 5=LibreView
     * @param days 0 = all available history
     */
    public static boolean exportTo(int fd, int type, float days) {
        if (!started) {
            Log.e(LOG_ID, "exportTo before engine start");
            return false;
        }
        // NOTE: the native exporters (exportdata/exportnums) abort when iterating the nums
        // store from the redesigned app's init state — the legacy export runs only after the
        // full OpenGL-curve/UI bring-up, which sets state these iterators assume. Calling them
        // here SIGABRTs the process, so the native export is gated off until it can be
        // debugged with the device + live data. The export UI is otherwise complete.
        if (!EXPORT_NATIVE_READY) {
            Log.w(LOG_ID, "exportTo gated: native export pending device-side validation");
            return false;
        }
        try {
            boolean ok = Natives.exportdata(type, fd, days);
            Log.i(LOG_ID, "exportTo type=" + type + " days=" + days + " ok=" + ok);
            return ok;
        } catch (Throwable t) {
            Log.e(LOG_ID, "exportTo failed", t);
            return false;
        }
    }

    /** Flip on once the native export is validated against live data on a device. */
    private static final boolean EXPORT_NATIVE_READY = false;

    /** The most recent stored events (newest last), up to {@code max}. */
    public static List<tk.glucodata.nums.item> recentEvents(int max) {
        List<tk.glucodata.nums.item> out = new ArrayList<>();
        if (!started) {
            return out;
        }
        try {
            int n = Natives.getlastNum(numsLive);
            for (int pos = Math.max(0, n - max); pos < n; pos++) {
                try {
                    tk.glucodata.nums.item it = Natives.getNumitem(numsLive, pos);
                    if (it != null) {
                        out.add(it);
                    }
                } catch (Throwable ignore) {
                    // best-effort read-back
                }
            }
        } catch (Throwable t) {
            Log.e(LOG_ID, "recentEvents failed", t);
        }
        return out;
    }
}
