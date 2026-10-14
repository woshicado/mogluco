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
            // Settings are loaded; before startthreads() starts the upload thread from them.
            revertInterruptedPushOnce(app);
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

    // Native limits, in bytes of the UTF-8 the JNI side converts to (javasettings.cpp copies
    // without a bounds check): setlibreemail writes the text plus a terminator into
    // libreemail[256]; setlibrepass converts into char tmp[37] (text plus terminator) and stores
    // into librepass[36]. Anything longer overruns the native stack and the settings fields that
    // follow in the mmapped settings file.
    private static final int LIBRE_EMAIL_MAX_BYTES = 255;
    private static final int LIBRE_PASSWORD_MAX_BYTES = 36;

    // The last setLibreviewCredentials() stored nothing: the native side still holds the
    // credentials from before, so a fetch must not run with them.
    private static volatile boolean credentialsRefused;

    /**
     * Store the LibreView credentials/region used for the account-ID fetch and uploads. Refuses
     * e-mail/password that do not fit the native settings fields; nothing is stored then.
     *
     * @return null when stored, otherwise a short message saying what was refused (it is also
     *     left in {@link #libreviewStatus()}, and {@link #fetchAccountId()} will not start).
     */
    @androidx.annotation.Nullable
    public static String setLibreviewCredentials(String email, String password, int country) {
        String mail = email == null ? "" : email;
        String pass = password == null ? "" : password;
        String refused = null;
        if (!started) {
            refused = "Engine not started.";
        } else if (jniUtf8Length(mail) > LIBRE_EMAIL_MAX_BYTES) {
            refused = "E-mail too long: at most " + LIBRE_EMAIL_MAX_BYTES + " bytes can be stored.";
        } else if (jniUtf8Length(pass) > LIBRE_PASSWORD_MAX_BYTES) {
            refused = "Password too long: at most " + LIBRE_PASSWORD_MAX_BYTES
                + " bytes can be stored (letters like ä or € count as 2–3).";
        }
        credentialsRefused = refused != null;
        if (refused != null) {
            Libreview.librestatus = refused;
            Log.e(LOG_ID, "setLibreviewCredentials refused: " + refused);
            return refused;
        }
        Natives.setlibreemail(mail);
        Natives.setlibrepass(pass);
        Natives.setLibreCountry(country);
        return null;
    }

    /**
     * Upper bound of the bytes JNI's (modified) UTF-8 conversion writes for {@code s}: U+0000 takes
     * two bytes and a surrogate pair is counted as two 3-byte units (ART writes 4 for the pair), so
     * this never undercounts whatever the runtime does.
     */
    private static int jniUtf8Length(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += (c >= 0x0001 && c <= 0x007F) ? 1 : (c <= 0x07FF ? 2 : 3);
        }
        return n;
    }

    public static String libreviewEmail() {
        return started ? safe(Natives.getlibreemail()) : "";
    }

    public static int libreviewCountry() {
        return started ? Natives.getLibreCountry() : 0;
    }

    // Marks of the account-ID fetch started last (this process only), for fetchedAccountId(). The
    // stored account ID stays in place during a fetch — a failed one must not cost a working ID —
    // so "the fetch delivered" has to be told apart from "the old ID is still there".
    private static volatile boolean accountFetchStarted;
    private static volatile boolean accountFetchDelivered;
    private static volatile String accountIdAtFetch = "";
    private static volatile String userTokenAtFetch = "";

    /** What {@link Libreview#postgetauth} leaves as status right after storing a received ID. */
    private static final String STATUS_ACCOUNT_RECEIVED = "Received AccountID";

    /**
     * Triggers an asynchronous LibreView account-ID fetch (the native thread contacts the server
     * using the stored email/password). Poll {@link #fetchedAccountId()} for the result and
     * {@link #libreviewStatus()} for progress/errors. Mirrors the legacy "Get Account ID → From
     * Libreview".
     *
     * @return false when no fetch was started (engine not started, or the last
     *     {@link #setLibreviewCredentials} was refused).
     */
    public static boolean fetchAccountId() {
        if (!started || credentialsRefused) {
            return false;
        }
        accountIdAtFetch = safe(Natives.getlibreAccountID());
        userTokenAtFetch = safe(Natives.getlibreUserToken(true));
        accountFetchDelivered = false;
        accountFetchStarted = true;
        // Drop the outcome of an earlier attempt, so neither its error nor its "received" is read
        // as this one's. The native thread writes the status again as it goes.
        Libreview.librestatus = Libreview.nothing;
        Natives.setlibreAccountIDnumber(-1L); // legacy sentinel: derive number from the fetched string
        Natives.askServerforAccountID();
        return true;
    }

    /**
     * The account ID delivered by the fetch started with the last {@link #fetchAccountId()}; empty
     * while that fetch is still running, after it failed, or when none was started in this
     * process. Unlike {@link #libreAccountId()} it never returns the ID that was already stored
     * before the fetch as if it had just arrived.
     */
    public static String fetchedAccountId() {
        if (!started || !accountFetchStarted) {
            return "";
        }
        String id = safe(Natives.getlibreAccountID());
        if (!accountFetchDelivered) {
            // A successful login stores a new user token and the account ID, then sets the
            // status. The status alone is not enough: with uploading on, the thread goes on to
            // post and overwrites it between two polls.
            boolean delivered = STATUS_ACCOUNT_RECEIVED.equals(Libreview.librestatus)
                || !id.equals(accountIdAtFetch)
                || !safe(Natives.getlibreUserToken(true)).equals(userTokenAtFetch);
            if (!delivered) {
                return "";
            }
            accountFetchDelivered = true;
        }
        return id;
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

    /**
     * Whether the user has background uploading on. The native switch that {@link #pushOnce()}
     * holds on for the length of its one-shot window does not count.
     */
    public static boolean isUploadEnabled() {
        return started && !uploadRevertPending && Natives.getuselibreview();
    }

    // Bumped by every explicit upload-toggle (the persistent "Upload automatically" switch, pushNow).
    // pushOnce()'s delayed revert captures this and only fires if it hasn't changed since — so a
    // persistent enable made inside the one-shot window cancels the pending auto-off.
    private static volatile int uploadIntentGen = 0;

    // pushOnce() switched uploading on for a one-shot and still owes the switch-off. Mirrored in
    // SharedPreferences (the native settings have no field for it) so that a process death inside
    // the window cannot leave uploading on for good: Engine.start() settles the debt.
    private static final String ENGINE_PREFS = "engine";
    private static final String KEY_UPLOAD_REVERT_PENDING = "libreview_upload_revert_pending";
    private static volatile boolean uploadRevertPending;

    /** How long pushOnce() keeps uploading on when no upload completes, and how often it looks. */
    private static final long PUSH_ONCE_MAX_MS = 120_000L;
    private static final long PUSH_ONCE_POLL_MS = 2_000L;

    // commit(), not apply(): the marker has to be on disk before the switch it guards is flipped,
    // and its removal before a deliberate "upload on" could be undone by a stale marker.
    private static void setUploadRevertPending(boolean pending) {
        if (uploadRevertPending == pending) {
            return;
        }
        uploadRevertPending = pending;
        try {
            android.content.SharedPreferences.Editor edit =
                Applic.app.getSharedPreferences(ENGINE_PREFS, Context.MODE_PRIVATE).edit();
            if (pending) {
                edit.putBoolean(KEY_UPLOAD_REVERT_PENDING, true);
            } else {
                edit.remove(KEY_UPLOAD_REVERT_PENDING);
            }
            if (!edit.commit()) {
                Log.e(LOG_ID, "setUploadRevertPending(" + pending + ") not written");
            }
        } catch (Throwable t) {
            Log.e(LOG_ID, "setUploadRevertPending(" + pending + ") failed", t);
        }
    }

    /**
     * Switches uploading back off when the previous process died inside a {@link #pushOnce()}
     * window. Runs from {@link #start} once the settings are loaded and before the native upload
     * thread is started from them. Not on the watch: its native library has no LibreView code.
     */
    private static void revertInterruptedPushOnce(Context app) {
        if (Applic.isWearable) {
            return;
        }
        try {
            android.content.SharedPreferences prefs =
                app.getSharedPreferences(ENGINE_PREFS, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_UPLOAD_REVERT_PENDING, false)) {
                Natives.setuselibreview(false);
                prefs.edit().remove(KEY_UPLOAD_REVERT_PENDING).commit();
                Log.i(LOG_ID, "reverted the upload switch left on by an interrupted pushOnce");
            }
        } catch (Throwable t) {
            Log.e(LOG_ID, "revertInterruptedPushOnce failed", t);
        }
    }

    /** Enable/disable uploading collected data to LibreView. */
    public static void setUploadEnabled(boolean on) {
        if (started) {
            uploadIntentGen++;
            setUploadRevertPending(false); // the user decided; nothing left to revert
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
            setUploadRevertPending(false); // uploading stays on by request
            Natives.setuselibreview(true);
            Natives.wakelibreview(0);
            Log.i(LOG_ID, "pushNow");
        }
    }

    /**
     * One-shot push that does NOT leave background uploading on. The native upload thread only POSTs
     * while uploading is enabled, so we enable it and wake the thread; if the user hadn't turned
     * background upload on, it is switched off again once an upload went through — or, when none
     * does (nothing to send, no network, login failed), after {@link #PUSH_ONCE_MAX_MS}. A fixed
     * short window would cut off a cold start: the thread first fetches its configuration and logs
     * in, and exits without posting if the switch is off again by then. The owed switch-off is
     * persisted, see {@link #revertInterruptedPushOnce}. The persistent "Upload automatically"
     * toggle is left untouched.
     */
    public static void pushOnce() {
        if (!started) {
            return;
        }
        boolean wasOn = Natives.getuselibreview();
        if (!wasOn) {
            setUploadRevertPending(true); // on disk before the switch is flipped
        }
        Natives.setuselibreview(true);
        Natives.wakelibreview(0);
        if (!wasOn) {
            final int gen = uploadIntentGen;
            // Every successful POST assigns a new timestamp String (one POST carries everything
            // pending), so a changed reference means this push is through.
            final String postedBefore = Libreview.posttime;
            final long deadline = android.os.SystemClock.elapsedRealtime() + PUSH_ONCE_MAX_MS;
            final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // Only revert if the user hasn't touched the upload toggle since (which bumps
                    // uploadIntentGen and settles the marker) — otherwise a persistent "Upload
                    // automatically" enabled inside this window would be silently turned back off.
                    if (!started || gen != uploadIntentGen) {
                        return;
                    }
                    boolean posted = Libreview.posttime != postedBefore;
                    if (!posted && android.os.SystemClock.elapsedRealtime() < deadline) {
                        handler.postDelayed(this, PUSH_ONCE_POLL_MS);
                        return;
                    }
                    Natives.setuselibreview(false);
                    setUploadRevertPending(false);
                    Log.i(LOG_ID, "pushOnce reverted (posted=" + posted + ")");
                }
            }, PUSH_ONCE_POLL_MS);
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

    /**
     * Guard on the points read per sensor and stream; never the limit for real data. The poll
     * stream holds one value per MINUTE (a Libre 3 stores it by lifecount: 20,160 over 14 days,
     * 21,600 over 15), the historic array one per five. The natives return the next position in
     * 16 bits and the loops below only ever advance it, so a stream cannot yield more than this
     * many points anyway — a lower cap silently drops the newest part of the poll stream.
     */
    private static final int MAX_HISTORY_POINTS = 1 << 16;

    /**
     * A sensor the store knows about (past or present): its serial, activation time, the engine's
     * official end of its wear time and its last stored reading (each 0 when unknown).
     */
    public static final class StoredSensor {
        public final String serial;
        public final long startMillis;
        public final long officialEndMillis;
        public final long lastReadingMillis;

        StoredSensor(String serial, long startMillis, long officialEndMillis, long lastReadingMillis) {
            this.serial = serial;
            this.startMillis = startMillis;
            this.officialEndMillis = officialEndMillis;
            this.lastReadingMillis = lastReadingMillis;
        }
    }

    /**
     * Every sensor in the store (past + present) with its activation time, in store order. The
     * calendar uses this to name the sensor that was active on a past day — the repository's
     * {@code sensor} flow only ever holds the CURRENT one. Activation comes from the sensor's own
     * start time; when the engine doesn't know it, the first stored reading stands in. Sensors
     * with neither are skipped. Synchronized for the same reason as {@link #glucoseHistory()}.
     * Read straight from the stored sensor data — no {@link Natives#getdataptr}, which would
     * allocate a stream object per call and reset the live sensor's error state. Empty on the
     * watch: nothing there shows past sensors, and its native library lacks these natives.
     */
    public static synchronized java.util.List<StoredSensor> storedSensors() {
        java.util.ArrayList<StoredSensor> out = new java.util.ArrayList<>();
        if (!started || Applic.isWearable) {
            return out;
        }
        try {
            long[] sensors = Natives.allSensorPtrs();
            if (sensors == null) {
                return out;
            }
            for (long sensorptr : sensors) {
                if (sensorptr == 0L) {
                    continue;
                }
                String serial = Natives.namefromSensorptr(sensorptr);
                if (serial == null || serial.isEmpty()) {
                    continue;
                }
                long[] life = Natives.lifefromSensorptr(sensorptr);
                long start = life[0] * 1000L;
                long officialEnd = life[1] * 1000L;
                if (start <= 0L) {
                    long timeSec = Natives.streamfromSensorptr(sensorptr, 0) & 0xFFFFFFFFL;
                    start = timeSec * 1000L;
                    officialEnd = 0L;
                }
                if (start > 0L) {
                    out.add(new StoredSensor(serial, start, officialEnd, life[2] * 1000L));
                }
            }
        } catch (Throwable th) {
            Log.e(LOG_ID, "storedSensors failed", th);
        }
        return out;
    }

    /**
     * Reads the full stored glucose history (1-minute live values, 5-minute historic values where
     * those are missing) for all stored sensors (past + present), as a flat array of
     * {@code [timeMillis, mgdl, timeMillis, mgdl, …]} ordered by time. Empty if the engine isn't
     * started or no sensor has data. This is what backfills the chart/calendar/export with the
     * sensor's pre-app-start readings (the engine stores them via saveLibre3History/saveLibre3MinuteL).
     * Where two sensors delivered side by side only one of them is in it ({@link #overlapSpans}).
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
        // real gap), so historic fills gaps without duplicating the live stream. Both are kept per
        // sensor (in store order) with the sensor's start: mergeHistory first leaves out a sensor
        // for as long as its successor delivered beside it.
        java.util.ArrayList<java.util.ArrayList<long[]>> polls = new java.util.ArrayList<>();
        java.util.ArrayList<java.util.ArrayList<long[]>> historics = new java.util.ArrayList<>();
        long[] starts = new long[0];
        try {
            // All stored sensors (past + present), so the chart/calendar show the full history,
            // not just sensors active in the last day. Read-only enumeration.
            long[] sensors = Natives.allSensorPtrs();
            if (sensors == null) {
                return new long[0];
            }
            starts = new long[sensors.length]; // 0 (not known) until a sensor is read in full
            for (long sensorptr : sensors) {
                if (sensorptr == 0L) {
                    continue;
                }
                java.util.ArrayList<long[]> poll = new java.util.ArrayList<>();
                java.util.ArrayList<long[]> historic = new java.util.ArrayList<>();
                int index = polls.size();
                polls.add(poll);
                historics.add(historic);
                long firstPoll = 0L;
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
                    if (firstPoll == 0L) {
                        firstPoll = timeSec * 1000L; // only for sensorStartMillis' fallback
                    }
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
                starts[index] = sensorStartMillis(sensorptr, firstPoll);
            }
        } catch (Throwable t) {
            Log.e(LOG_ID, "glucoseHistory failed", t);
        }
        return mergeHistory(java.util.Arrays.copyOf(starts, polls.size()), polls, historics);
    }

    /**
     * What orders the stored sensors for {@link #overlapSpans}, in epoch millis, 0 when not known:
     * a sensor's activation time where the engine has one, else the time of its first live
     * reading in the store ({@code firstPollMillis}, 0 = it has none). On the watch always the
     * latter: its native library lacks {@link Natives#lifefromSensorptr}. Sensors worn one after
     * another come out in the same order either way. Never throws — an order that is not known
     * only leaves the sensor as it is.
     */
    private static long sensorStartMillis(long sensorptr, long firstPollMillis) {
        if (!Applic.isWearable) {
            try {
                long[] life = Natives.lifefromSensorptr(sensorptr);
                if (life != null && life.length > 0 && life[0] > 0L) {
                    return life[0] * 1000L;
                }
            } catch (Throwable th) {
                Log.e(LOG_ID, "sensorStartMillis failed", th);
            }
        }
        return firstPollMillis;
    }

    /**
     * How long past its last live reading a sensor still stands alone against the one before it
     * (see {@link #overlapSpans}): what counts as a real hole in a series (the repository's
     * GAP_THRESHOLD_MS), and well over the minute to the next reading of a sensor that delivers.
     */
    static final long OVERLAP_TAIL_MS = 15 * 60 * 1000L;

    /**
     * One sensor at a time in the history too, as on the live path
     * ({@code NativeGlucoseRepository.followsSensor}): a replaced sensor that was not deactivated
     * keeps transmitting next to its successor, and the store keeps both, minute by minute.
     * Returns, per sensor, the stretches in which it contributes nothing to the history, as
     * {@code [from, to, from, to, …]} (both inclusive; empty: all of it stays) — one for every
     * LATER-ACTIVATED sensor: from that one's first live reading to its last, and
     * {@link #OVERLAP_TAIL_MS} beyond. In there the later sensor stands alone, holes included.
     *
     * <p>Live readings, because that is when the later sensor was in use on this device: a history
     * it brought along from before (a sensor taken over from another device) only fills holes of
     * the sensor that delivered here then, as any historic point does. And up to its last one,
     * because a sensor that has stopped for good — ended early, or taken off again — must not go
     * on hiding one that is still worn and delivers: from a quarter of an hour after its last
     * reading the earlier sensor counts again. (The live path goes back to it when the later one
     * has ended; a later one that is merely silent for longer keeps the live path, while the
     * history shows what the earlier one stored meanwhile — as it always did.)
     *
     * <p>"Later" is a known start time greater than this sensor's known one, as on the live path.
     * A sensor whose start is not known (0) is never left out and hides no other, nor do two
     * sensors with the same start: in doubt both stay in, as before. A sensor that ended before
     * the next one delivered — the normal case — has no reading in that one's stretch, so nothing
     * changes for it. This only shapes what {@link #glucoseHistory()} returns; the store is not
     * touched. Public only for the unit tests (HistoryOverlapTest in :app).
     *
     * @param startMillis activation per sensor, 0 = not known
     * @param firstLiveMillis first live reading per sensor, {@code Long.MAX_VALUE} = it has none
     * @param lastLiveMillis last live reading per sensor (not read where it has none)
     */
    public static long[][] overlapSpans(long[] startMillis, long[] firstLiveMillis, long[] lastLiveMillis) {
        long[][] spans = new long[startMillis.length][];
        for (int i = 0; i < spans.length; i++) {
            java.util.ArrayList<Long> hidden = new java.util.ArrayList<>();
            if (startMillis[i] > 0L) {
                for (int j = 0; j < startMillis.length; j++) {
                    if (startMillis[j] > startMillis[i] && firstLiveMillis[j] != Long.MAX_VALUE) {
                        hidden.add(firstLiveMillis[j]);
                        hidden.add(lastLiveMillis[j] + OVERLAP_TAIL_MS);
                    }
                }
            }
            spans[i] = new long[hidden.size()];
            for (int k = 0; k < spans[i].length; k++) {
                spans[i][k] = hidden.get(k);
            }
        }
        return spans;
    }

    /** Whether {@code timeMillis} lies in one of {@code spans} ({@code [from, to, …]}, inclusive). */
    private static boolean within(long[] spans, long timeMillis) {
        for (int k = 0; k + 1 < spans.length; k += 2) {
            if (timeMillis >= spans[k] && timeMillis <= spans[k + 1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * The flat, time-ordered history ({@link #glucoseHistory()}) from what was read per sensor:
     * its start time (0 = not known), its poll stream (the live readings) and its historic
     * stream, as {@code [timeMillis, mgdl]} points. Each sensor is first left out where a
     * later-activated one delivered ({@link #overlapSpans}; a reading timed before its own
     * sensor's start — a wrong clock — begins nothing), then the streams are merged as ever.
     * Where no two sensors overlap the result is what it was before any of this. Pure; public
     * only for the unit tests (HistoryOverlapTest in :app).
     */
    public static long[] mergeHistory(
            long[] startMillis,
            java.util.List<? extends java.util.List<long[]>> polls,
            java.util.List<? extends java.util.List<long[]>> historics) {
        long[] firsts = new long[startMillis.length];
        long[] lasts = new long[startMillis.length];
        for (int i = 0; i < firsts.length; i++) {
            firsts[i] = Long.MAX_VALUE;
            for (long[] p : polls.get(i)) {
                if (p[0] >= startMillis[i]) {
                    firsts[i] = Math.min(firsts[i], p[0]);
                    lasts[i] = Math.max(lasts[i], p[0]);
                }
            }
        }
        long[][] spans = overlapSpans(startMillis, firsts, lasts);
        java.util.ArrayList<long[]> poll = new java.util.ArrayList<>();
        java.util.ArrayList<long[]> historic = new java.util.ArrayList<>();
        for (int i = 0; i < spans.length; i++) {
            for (long[] p : polls.get(i)) {
                if (!within(spans[i], p[0])) {
                    poll.add(p);
                }
            }
            for (long[] h : historics.get(i)) {
                if (!within(spans[i], h[0])) {
                    historic.add(h);
                }
            }
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
