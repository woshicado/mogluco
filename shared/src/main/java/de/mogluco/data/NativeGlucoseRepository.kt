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

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import tk.glucodata.Engine
import tk.glucodata.Sensors

/**
 * Live [GlucoseRepository] backed by the native engine (:core).
 *
 * Registers as the engine [Engine.Listener] and boots the engine. New readings the native
 * side pushes via Applic.doglucose flow straight into [latest]. With no sensor connected
 * (e.g. on an emulator) no readings arrive and [latest] stays null — the home screen then
 * shows its "waiting for sensor" state, which is correct.
 *
 * Display preferences (unit / target range / reading display) mirror the app's [DisplayPrefs],
 * the same source the alarm controller, notification and AOD read — so a Settings change
 * applies immediately in native mode, exactly as it does in sample mode.
 *
 * @param onReading invoked with each reading's mg/dL on the engine thread — the seam the
 *   phone uses to evaluate glucose alarms and the watch uses to drive its own alarm
 *   controller. Exceptions are swallowed (a listener bug must not drop the reading).
 */
class NativeGlucoseRepository(
    context: Context,
    private val prefs: DisplayPrefs,
    private val onReading: (mgdl: Double, timeMillis: Long) -> Unit = { _, _ -> },
) : GlucoseRepository {

    private val appContext = context.applicationContext
    // App-lifetime scope for the (CPU-bound) history load so it never blocks the main thread.
    // The repository is an application singleton, so this scope intentionally lives forever.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val historyLoading = AtomicBoolean(false)
    // Debounces invalidate-triggered history reloads: a phone↔watch mirror sync fires a burst of
    // invalidates, and each reload walks the native sensor tables off-thread. Coalescing the burst
    // into a single reload after it settles keeps that off-thread walk from overlapping the native
    // writers as often. (This narrows, but does not fully close, the read/write window — the
    // complete fix is a native copy-under-lock; deferred to avoid destabilizing the BLE path.)
    @Volatile private var invalidateReloadJob: Job? = null
    private val _latest = MutableStateFlow<GlucoseReading?>(null)
    private val _history = MutableStateFlow<List<GlucosePoint>>(emptyList())

    private val _sensor = MutableStateFlow<SensorInfo?>(null)

    // Running EMA for the smoothed live trace (see appendPoint / GlucosePoint.smoothed).
    @Volatile private var liveEma: Double? = null

    // --- Connection/handshake failure tracking (upstream issue #405) ---
    // The native engine streams readings only after a successful Libre 3 handshake. When the
    // handshake keeps failing the sensor connects and drops repeatedly without ever producing a
    // reading; we surface that as a SensorError so the user isn't left staring at a sensor that
    // appears to warm up forever. Guarded by [sensorLock]; touched from the engine listener and
    // the BLE callback (different threads).
    private val sensorLock = Any()
    private var sessionSerial: String? = null
    private var lastReadingMillis = 0L
    private var connectionFailures = 0
    private var connectionError: SensorError? = null
    // Last activation time the engine reported, per serial: the engine can transiently report 0
    // (no dataptr yet) for a sensor whose start it gave us earlier. Guarded by [sensorLock].
    private val knownActivations = HashMap<String, Long>()

    // --- Automatic gap-fill on sensor reconnect ---
    // The Libre 3's automatic backfill only pulls forward from a high-water mark, so an interior gap
    // (e.g. from being out of range) is never re-fetched. On a reconnect we check for such a gap
    // within the sensor's ~8h buffer and, if found, silently trigger the deep pull that fills it.
    // Guarded by [sensorLock]. lastAutoPullMillis rate-limits it so frequent reconnects don't thrash.
    private var sensorConnected = false
    @Volatile private var lastAutoPullMillis = 0L

    override val latest: Flow<GlucoseReading?> = _latest.asStateFlow()
    override val history: Flow<List<GlucosePoint>> = _history.asStateFlow()
    // Unit / target range / reading display come from [DisplayPrefs] (matching
    // MockGlucoseRepository): prefs are the single source of truth for display settings, so the
    // home card, chart, lists, notification, AOD and AlarmController all agree the moment the
    // user changes them. The engine keeps its own unit/target for its native paths; the
    // redesigned UI deliberately doesn't snapshot those.
    override val unit: Flow<GlucoseUnit> = prefs.unit
    override val targetRange: Flow<ClosedFloatingPointRange<Double>> =
        combine(prefs.targetLow, prefs.targetHigh) { lo, hi -> lo.toDouble()..hi.toDouble() }
    override val sensor: Flow<SensorInfo?> = _sensor.asStateFlow()
    override val currentSensor: SensorInfo? get() = _sensor.value

    private val _sensorHistory = MutableStateFlow<List<SensorInfo>>(emptyList())
    override val sensorHistory: Flow<List<SensorInfo>> = _sensorHistory.asStateFlow()
    override val currentReadingMillis: Long get() = _latest.value?.epochMillis ?: 0L

    /** Deactivation clears the active-sensor card + current reading so the UI returns to "no sensor".
     *  Past history is kept. Per-session connection tracking is reset so a later re-activation starts
     *  clean. */
    override fun clearActiveSensor() {
        synchronized(sensorLock) {
            sessionSerial = null
            lastReadingMillis = 0L
            connectionFailures = 0
            connectionError = null
            sensorConnected = false
        }
        _sensor.value = null
        _latest.value = null
    }

    override fun showActivatedSensor() {
        synchronized(sensorLock) {
            if (_sensor.value != null) return // already showing a sensor
            val serial = runCatching { Sensors.activeSensors().firstOrNull() }.getOrNull()
            if (serial.isNullOrEmpty()) return
            beginSessionIfNeeded(serial)
            sensorConnected = false
            // Populates _sensor from the engine's known activation time (no-op if it isn't known
            // yet); the home then shows the sensor card in a connecting/warming state until the first
            // reading lands.
            updateSensor(serial, alarm = 0)
        }
    }
    override val readingDisplay: Flow<ReadingDisplay> = prefs.readingDisplay

    init {
        // Re-derive the smoothed trace when smoothing is toggled or its strength changes: a pure
        // in-memory recompute over the raw series already in [_history] — no JNI re-fetch/reparse of
        // the native store the BLE writer is using (loadHistory is only for the store itself). Runs
        // on [scope] (Dispatchers.Default); conflate so a fast slider drag skips stale intermediates.
        scope.launch {
            combine(prefs.smoothing, prefs.smoothingAlpha) { on, a -> on to a }
                .drop(1)
                .conflate()
                .collect { (on, alpha) ->
                    val resmoothed = _history.updateAndGet { it.smoothed(on, alpha.toDouble()) }
                    // Reseed the live EMA so the next appendPoint continues from the new trace.
                    liveEma = resmoothed.lastOrNull()?.calibratedMgdl
                }
        }
        // The native listener delivers the sensor serial + an alarm/state code alongside each
        // reading. serial → _sensor; the state code → SensorInfo.error (unavailable / out-of-range)
        // which drives the home warning banner. raw==calibrated remains until the engine exposes
        // the pre-calibration value separately. [Engine.Listener] is a Java interface, so serial
        // is a platform type: declare it nullable (like Sensors.StateListener) — a null from the
        // native side must not throw in here, because Engine swallows listener exceptions and the
        // reading would be silently dropped.
        Engine.setListener { serial: String?, mgdl, _, rate, timeMillis, alarm ->
            val cur = _latest.value
            // The engine can replay a backlog or deliver an out-of-order reading on reconnect. Only
            // a reading at least as new as the one we already show is "live"; an older one must not
            // rewind the reading card (a stale value shown as current) or ring a spurious alarm —
            // but it is still valid history, so it is appended below regardless.
            val isLive = cur == null || timeMillis >= cur.epochMillis
            if (isLive) {
                _latest.value = GlucoseReading(
                    calibratedMgdl = mgdl.toDouble(),
                    rawMgdl = mgdl.toDouble(),
                    epochMillis = timeMillis,
                    trend = TrendArrow.fromRate(rate.toDouble()),
                    ratePerMin = rate.toDouble(),
                )
            }
            // A reading proves the handshake succeeded and data is flowing: clear any connection
            // error and mark the session live before refreshing the banner.
            synchronized(sensorLock) {
                if (!serial.isNullOrEmpty()) beginSessionIfNeeded(serial)
                connectionError = null
                connectionFailures = 0
                if (isLive) lastReadingMillis = System.currentTimeMillis()
                updateSensor(serial, alarm)
            }
            // Evaluate glucose alarms against the LIVE reading only (a backfilled out-of-range
            // point must not alarm). Pass the reading's own timestamp so the watch's alarm
            // controller can track signal-loss by reading time.
            if (isLive) runCatching { onReading(mgdl.toDouble(), timeMillis) }
            // Accumulate a rolling window of points so the graph fills as readings arrive (backfill
            // included). Pre-existing history is loaded separately via loadHistory().
            appendPoint(GlucosePoint(timeMillis, mgdl.toDouble(), mgdl.toDouble()))
        }
        // Reflect BLE connection state into _sensor. On connect we refresh the card (so it shows
        // up during warm-up, before any reading). On disconnect we decide whether this is a
        // handshake failure worth surfacing (upstream issue #405) vs. a benign drop.
        Sensors.setStateListener { serial, connected, status, detail ->
            if (serial.isNullOrEmpty()) return@setStateListener
            var reconnected = false
            synchronized(sensorLock) {
                beginSessionIfNeeded(serial)
                if (connected) {
                    // A connect after we were disconnected is a reconnect — the moment to auto-fill a
                    // gap the sensor still holds (its ~8h buffer).
                    reconnected = !sensorConnected
                    sensorConnected = true
                    updateSensor(serial, alarm = 0)
                } else {
                    sensorConnected = false
                    handleDisconnect(serial, status, detail)
                }
            }
            if (reconnected) scheduleAutoGapFill()
        }
        Engine.start(appContext)
        // Bulk changes that bypass the per-reading callback (phone↔watch mirror sync,
        // /start settings bootstrap, network resets) → re-read the stored history, debounced so a
        // burst of invalidates collapses into one reload once it settles.
        Engine.addInvalidateListener { scheduleHistoryReload() }
        // Load the sensor's full STORED history (5-minute readings since activation) so the
        // chart/calendar/export show real back-data, not just readings since app launch.
        runCatching { loadHistory() }
        // Begin background scanning/connecting for activated sensors (must come after start()).
        runCatching { Sensors.start() }
    }

    /**
     * Replace [_history] with the engine's full stored history. The JNI fetch + parse is CPU-bound
     * and the history can be large (a year across many sensors), so it runs off the main thread.
     * Live readings keep landing via [appendPoint] (on the engine's thread) while it parses, so
     * the commit is a lock-free [MutableStateFlow.update] that folds any points appended since the
     * snapshot into the loaded list — a plain assignment could clobber them, or be clobbered by
     * them. Coalesced via [historyLoading] so overlapping calls don't double-load.
     */
    /** Coalesce a burst of engine invalidates into a single reload once the burst quiets. */
    private fun scheduleHistoryReload() {
        invalidateReloadJob?.cancel()
        invalidateReloadJob = scope.launch {
            delay(HISTORY_RELOAD_DEBOUNCE_MS)
            loadHistory()
        }
    }

    /**
     * On a sensor reconnect, silently fill any gap the sensor can still recover from its ~8h on-device
     * buffer. Waits [AUTO_GAPFILL_SETTLE_MS] first so the normal reconnect backfill + reload run — if
     * that already closed the gap we do nothing. Rate-limited by [AUTO_GAPFILL_MIN_INTERVAL_MS] so a
     * flapping connection can't trigger a pull every reconnect. The pull itself is idempotent
     * (already-stored records are no-ops) and stays silent (no toast), so the user never has to think
     * about pulling history manually.
     */
    private fun scheduleAutoGapFill() {
        scope.launch {
            delay(AUTO_GAPFILL_SETTLE_MS)
            val now = System.currentTimeMillis()
            if (now - lastAutoPullMillis < AUTO_GAPFILL_MIN_INTERVAL_MS) return@launch
            if (!hasRecentGap(now)) return@launch
            lastAutoPullMillis = now
            runCatching { Sensors.pullHistoryFromSensor(SENSOR_BUFFER_MINUTES, /* announce = */ false) }
        }
    }

    /**
     * True if the displayed history has a gap longer than [GAP_THRESHOLD_MS] between two consecutive
     * readings that both fall within the sensor's recoverable ~8h window (older gaps can't be pulled).
     */
    private fun hasRecentGap(now: Long): Boolean {
        val cutoff = now - SENSOR_BUFFER_MS
        var prev = 0L
        for (p in _history.value) {
            val t = p.epochMillis
            if (t < cutoff) continue
            if (prev != 0L && t - prev > GAP_THRESHOLD_MS) return true
            prev = t
        }
        return false
    }

    /**
     * Fold [additions] into [existing] as the *authoritative series over the span it covers*, keep
     * time order, and cap to the most-recent [MAX_POINTS]. The one place the history merge/cap
     * policy lives.
     *
     * [additions] is a canonical record for a time range — the engine store ([loadHistory]), a
     * mirror-peer catch-up batch ([mergeBackfill]), or one appended point. Over
     * `[additions.first .. additions.last]` it wins wholesale: every [existing] point in that span
     * is dropped and replaced by the additions. [existing] points OUTSIDE the span are kept — older
     * history the additions don't reach, and the live tail newer than the newest addition.
     *
     * This replaces a former within-30s *proximity* dedupe. The same physical reading reaches us on
     * two grids — the phone's ~1-minute live trace (wall-clock reception time) and the ~5-minute
     * historic/backfill points (the sensor's grid-aligned time) — offset by more than any safe
     * window. Proximity dedupe left those near-duplicates alive as a "forest of near-vertical
     * spikes," and a wider window (or rounding to a grid) only narrows the failure: a reading near a
     * boundary still splits, and genuine ~1-minute neighbors get wrongly collapsed. Range authority
     * is immune by construction — exactly one point per instant in the covered span, no proximity,
     * no rounding — regardless of the grid/clock offset between phone and watch.
     */
    private fun mergeCapped(existing: List<GlucosePoint>, additions: List<GlucosePoint>): List<GlucosePoint> {
        if (additions.isEmpty()) return capTail(existing)
        // The authoritative series must be time-sorted and exact-timestamp-unique (last wins) so the
        // span bounds are well-defined and the unique-timestamp invariant [windowed]/lowerBound rely
        // on holds. A single appended point (the common out-of-order case) is trivially both.
        val adds = if (additions.size == 1) {
            additions
        } else {
            val sorted = additions.sortedBy { it.epochMillis }
            val u = ArrayList<GlucosePoint>(sorted.size)
            for (p in sorted) {
                if (u.isNotEmpty() && u.last().epochMillis == p.epochMillis) u[u.size - 1] = p else u.add(p)
            }
            u
        }
        if (existing.isEmpty()) return capTail(adds)
        val spanStart = adds.first().epochMillis
        val spanEnd = adds.last().epochMillis
        // [existing] is time-sorted: keep the head older than the span, splice in the authoritative
        // additions, skip everything the span covers, then keep the live tail newer than it.
        val out = ArrayList<GlucosePoint>(existing.size + adds.size)
        var i = 0
        while (i < existing.size && existing[i].epochMillis < spanStart) { out.add(existing[i]); i++ }
        out.addAll(adds)
        while (i < existing.size && existing[i].epochMillis <= spanEnd) i++
        while (i < existing.size) { out.add(existing[i]); i++ }
        return capTail(out)
    }

    private fun capTail(points: List<GlucosePoint>): List<GlucosePoint> =
        if (points.size > MAX_POINTS) points.takeLast(MAX_POINTS) else points

    private fun loadHistory() {
        if (!historyLoading.compareAndSet(false, true)) return
        scope.launch {
            try {
                val flat = Engine.glucoseHistory() // already time-sorted; [timeMillis, mgdl, …]
                // The engine sorts by time, so duplicate 5-minute slots from overlapping sensors
                // (at a swap) are adjacent — collapse them in one linear pass (last value wins),
                // no map or second sort. Keeps timestamps unique (the readings list keys on them).
                val points = ArrayList<GlucosePoint>(flat.size / 2)
                var i = 0
                while (i + 1 < flat.size) {
                    val t = flat[i]
                    val mgdl = flat[i + 1].toDouble()
                    if (points.isNotEmpty() && points[points.size - 1].epochMillis == t) {
                        points[points.size - 1] = GlucosePoint(epochMillis = t, rawMgdl = mgdl, calibratedMgdl = mgdl)
                    } else {
                        points.add(GlucosePoint(epochMillis = t, rawMgdl = mgdl, calibratedMgdl = mgdl))
                    }
                    i += 2
                }
                if (points.isNotEmpty()) {
                    // Derive the smoothed trace over the full raw series, then seed the live EMA.
                    val smoothedPoints = points.smoothed(prefs.smoothing.value, prefs.smoothingAlpha.value.toDouble())
                    liveEma = smoothedPoints.last().calibratedMgdl
                    _history.update { cur ->
                        // The freshly re-smoothed store is authoritative over the span it covers;
                        // live-only points newer than the store snapshot are kept as the tail.
                        mergeCapped(cur, smoothedPoints)
                    }
                }
                // Same trigger as the history (start, invalidate, new sensor): refresh the list of
                // stored sensors so past calendar days can name the sensor that covered them.
                _sensorHistory.value = Engine.storedSensors()
                    .map { SensorInfo(id = it.serial, activationMillis = it.startMillis) }
                    .distinctBy { it.id }
                    .sortedBy { it.activationMillis }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                android.util.Log.w("NativeGlucoseRepo", "loadHistory failed", t)
            } finally {
                historyLoading.set(false)
            }
        }
    }

    /**
     * Merge catch-up readings from the mirror peer (reconnect backfill) into the displayed history.
     * The batch is authoritative over the span it covers (see [mergeCapped]), so it cleanly replaces
     * any provisional live points there instead of overlaying a second grid. Same CAS-safe
     * [MutableStateFlow.update] path as [appendPoint], and the points survive a later [loadHistory]
     * (which folds current history in with the store).
     */
    override fun mergeBackfill(points: List<Pair<Long, Double>>): Int {
        if (points.isEmpty()) return 0
        val incoming = points.map {
            GlucosePoint(epochMillis = it.first, rawMgdl = it.second, calibratedMgdl = it.second)
        }.smoothed(prefs.smoothing.value, prefs.smoothingAlpha.value.toDouble())
        val before = _history.value.size
        val after = _history.updateAndGet { cur -> mergeCapped(cur, incoming) }
        return (after.size - before).coerceAtLeast(0)
    }

    /**
     * Resets per-session connection tracking when a different sensor becomes active, so one
     * sensor's failures never bleed into the next. Must be called holding [sensorLock].
     */
    private fun beginSessionIfNeeded(serial: String) {
        if (sessionSerial != serial) {
            sessionSerial = serial
            lastReadingMillis = 0L
            connectionFailures = 0
            connectionError = null
        }
    }

    /**
     * Decides whether a disconnect is a handshake failure worth showing. Must be called holding
     * [sensorLock]. Ignores drops while data is actively flowing (transient signal loss). A
     * recorded handshake failure ([detail] non-null) is conclusive immediately; a bare error
     * status must recur [STATUS_FAIL_THRESHOLD] times so a single warm-up Bluetooth glitch is
     * not mistaken for the #405 failure.
     */
    private fun handleDisconnect(serial: String, status: Int, detail: String?) {
        val error = SensorError.fromConnection(status, detail) ?: return
        val streaming = lastReadingMillis != 0L &&
            System.currentTimeMillis() - lastReadingMillis <= STREAMING_WINDOW_MS
        if (streaming) return
        // During the 60-min warm-up a Libre 3 legitimately connects and drops several times before
        // it streams; a bare link drop there is not the #405 handshake failure. Only a recorded
        // handshake failure (detail != null) is conclusive during warm-up — otherwise ignore the
        // drop so a healthy warming-up sensor is not mislabeled "Sensor setup failed".
        if (detail == null && _sensor.value?.isWarmingUp() == true) return
        connectionFailures++
        if (detail != null || connectionFailures >= STATUS_FAIL_THRESHOLD) {
            connectionError = error
            updateSensor(serial, alarm = 0)
        }
    }

    /**
     * Populate [_sensor] from a reported serial and the per-reading [alarm]/state code (mapped to
     * an error banner). Fetches the activation time from the engine on first sight of a serial,
     * remembering it per serial in [knownActivations]. Must be called holding [sensorLock].
     */
    private fun updateSensor(serial: String?, alarm: Int) {
        if (serial.isNullOrEmpty()) return
        // Priority: a hardware fault (defective / replace / ended) is definitive; then a
        // connection/handshake failure (no data is flowing at all); then an out-of-range glucose
        // state. The hardware state comes from the engine's sensorerror/patchState.
        val error = SensorError.fromSensorState(Sensors.sensorState(serial))
            ?: connectionError
            ?: SensorError.fromGlucoseAlarm(alarm)
        val current = _sensor.value
        if (current != null && current.id == serial) {
            // Same sensor: just refresh the error state (banner appears/clears with each reading).
            if (current.error != error) _sensor.value = current.copy(error = error)
            return
        }
        val start = Sensors.sensorStartMillis(serial)
        if (start > 0L) knownActivations[serial] = start
        // 0 means the engine doesn't know the activation (yet). Fall back to the last value it
        // reported for this serial; if there has never been one, publish nothing rather than
        // fabricate an activation — a "now" fallback would invent a fresh 60-minute warm-up and
        // "day 1/14" for an established sensor. We re-enter here on every reading/connect while
        // [_sensor] doesn't hold this serial, so the card appears as soon as the engine knows.
        val activation = if (start > 0L) start else knownActivations[serial] ?: return
        _sensor.value = SensorInfo(
            id = serial,
            activationMillis = activation,
            error = error,
        )
        // A newly-connected sensor brings backfilled history with it; refresh.
        runCatching { loadHistory() }
    }

    private fun appendPoint(rawPoint: GlucosePoint) {
        // Smoothed trace: a causal EMA of raw (or raw itself when smoothing is off), stored as
        // calibratedMgdl so every consumer (graph, tooltips, lists) shows it consistently.
        val point = if (prefs.smoothing.value) {
            val a = prefs.smoothingAlpha.value.toDouble()
            val e = a * rawPoint.rawMgdl + (1 - a) * (liveEma ?: rawPoint.rawMgdl)
            liveEma = e
            rawPoint.copy(calibratedMgdl = e)
        } else {
            rawPoint.copy(calibratedMgdl = rawPoint.rawMgdl)
        }
        // Keep [_history] time-sorted and de-duped by timestamp while staying cheap per reading.
        // The list can hold a year+ of points, so the old "concat → associateBy → sort → takeLast"
        // rebuild on every reading was O(n log n) each minute; instead handle the common cases
        // (newest reading, or a refresh of the latest slot) in O(n) copy with no map/sort, and only
        // fall back to a full merge for the rare out-of-order/backfill case.
        // Runs on the engine's native thread while [loadHistory] commits from [scope], so the
        // write goes through the CAS in [MutableStateFlow.update] (the lambda is pure, safe to
        // retry) — a read-then-assign here could clobber a concurrent commit, or be clobbered.
        val t = point.epochMillis
        _history.update { cur ->
            when {
                cur.isEmpty() -> listOf(point)
                // Newest reading (the usual case): append, trimming the oldest only if over the
                // cap. Over the cap, build the trimmed tail + new point in a single allocation
                // (rather than concat-then-copy twice).
                t > cur.last().epochMillis -> {
                    if (cur.size >= MAX_POINTS) {
                        ArrayList<GlucosePoint>(MAX_POINTS).apply {
                            addAll(cur.subList(cur.size - MAX_POINTS + 1, cur.size))
                            add(point)
                        }
                    } else {
                        cur + point
                    }
                }
                // Same timestamp as the latest point (live refresh of the current slot): replace it.
                t == cur.last().epochMillis -> cur.dropLast(1) + point
                // Out-of-order / backfill (rare): merge, de-dupe by timestamp, re-sort, cap.
                else -> mergeCapped(cur, listOf(point))
            }
        }
    }

    // Glucose is owned by the native engine (it stores readings straight from the sensor) and
    // exposes no API to inject historic points or purge them. So on the live path these are
    // deliberate no-ops: a backup restore brings back settings + events, but not glucose (the
    // engine already holds the real sensor data). Logged, not crashed, and not falsely "succeeded"
    // — the false return tells callers nothing was deleted/imported.
    override fun deleteBefore(cutoffMillis: Long): Boolean {
        android.util.Log.i("NativeGlucoseRepo", "deleteBefore ignored: glucose store is engine-owned")
        return false
    }

    override fun importHistory(points: List<GlucosePoint>): Boolean {
        android.util.Log.i(
            "NativeGlucoseRepo",
            "importHistory ignored (${points.size} pts): glucose store is engine-owned",
        )
        return false
    }

    private companion object {
        // Retain the full long-term history the engine holds, not just the recent window: ~1.5
        // years of 5-minute readings, with headroom for live 1-minute points. The engine reloads
        // this on each launch (loadHistory), so it's a memory bound, not a data-loss cap.
        // At ~48 bytes/point this is ~14 MB worst case.
        const val MAX_POINTS = 300_000

        /** Readings within this window mean data is flowing — ignore transient drops. */
        const val STREAMING_WINDOW_MS = 6 * 60_000L

        /** Bare error statuses (no recorded handshake failure) must recur this many times before
         *  we alarm, so a one-off Bluetooth glitch during warm-up doesn't trip the banner. */
        const val STATUS_FAIL_THRESHOLD = 3

        /** Quiet period after the last engine invalidate before reloading history (debounce). */
        const val HISTORY_RELOAD_DEBOUNCE_MS = 1_500L

        // --- Automatic gap-fill on reconnect ---
        /** The Libre 3's on-device historic buffer depth (what a pull can recover). */
        const val SENSOR_BUFFER_MINUTES = 8 * 60
        const val SENSOR_BUFFER_MS = SENSOR_BUFFER_MINUTES * 60_000L
        /** A gap between consecutive readings longer than this (vs. the 5-min historic spacing) is a
         *  real hole worth pulling. */
        const val GAP_THRESHOLD_MS = 15 * 60_000L
        /** Let the normal reconnect backfill + reload land before checking whether a gap remains. */
        const val AUTO_GAPFILL_SETTLE_MS = 30_000L
        /** Don't auto-pull more than once per this interval, so a flapping link can't thrash. */
        const val AUTO_GAPFILL_MIN_INTERVAL_MS = 15 * 60_000L
    }
}
