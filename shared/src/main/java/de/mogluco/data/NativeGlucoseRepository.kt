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

    // Running EMA for the smoothed live trace (see appendPoint / GlucosePoint.smoothed): the last
    // smoothed point, i.e. the EMA in calibratedMgdl together with the time it belongs to — the
    // next reading continues from it only when it is close in time.
    @Volatile private var liveEma: GlucosePoint? = null

    // --- Connection/handshake failure tracking (upstream issue #405) ---
    // The native engine streams readings only after a successful Libre 3 handshake. When the
    // handshake keeps failing the sensor connects and drops repeatedly without ever producing a
    // reading; we surface that as a SensorError so the user isn't left staring at a sensor that
    // appears to warm up forever. Guarded by [sensorLock]; touched from the engine listener and
    // the BLE callback (different threads).
    private val sensorLock = Any()
    private var sessionSerial: String? = null
    private var lastReadingMillis = 0L
    // When the followed sensor ([sessionSerial]) last delivered a reading at all (wall clock) — what
    // [followsSensor] means by "delivering". Unlike [lastReadingMillis] it also counts a reading
    // that was not newer than the value on screen.
    private var lastHeardMillis = 0L
    // Since when this process could have heard a sensor at all (the repository is created with the
    // application) — see [followsSensor].
    private val createdMillis = System.currentTimeMillis()
    private var connectionFailures = 0
    private var connectionError: SensorError? = null
    // Last activation time the engine reported, per serial: the engine can transiently report 0
    // (no dataptr yet) for a sensor whose start it gave us earlier. Guarded by [sensorLock].
    private val knownActivations = HashMap<String, Long>()
    // The card in [_sensor] was published at the process start from the engine's list and that
    // sensor has not reported since ([showSensorAtStart]). Guarded by [sensorLock].
    private var shownBeforeFirstEvent = false
    // A newly activated sensor whose connection events the live path leaves out, because the
    // previous sensor is still delivering ([followsSensor]): its setup failures are counted here,
    // apart from the followed sensor's [connectionFailures], and shown as [newSensorFault].
    // Guarded by [sensorLock].
    private val newSensorSetup = NewSensorSetup(STATUS_FAIL_THRESHOLD)
    private val _newSensorFault = MutableStateFlow<SensorInfo?>(null)

    // --- Automatic gap-fill on sensor reconnect ---
    // The Libre 3's automatic backfill only pulls forward from a high-water mark, so an interior gap
    // (e.g. from being out of range) is never re-fetched. On a reconnect we check for such a gap
    // within the sensor's ~8h buffer and, if found, silently trigger the deep pull that fills it.
    // Guarded by [sensorLock]. lastAutoPullMillis rate-limits it so frequent reconnects don't thrash.
    private var sensorConnected = false
    @Volatile private var lastAutoPullMillis = 0L

    override val latest: Flow<GlucoseReading?> = _latest.asStateFlow()
    override val history: Flow<List<GlucosePoint>> = _history.asStateFlow()

    private val _historyLoaded = MutableStateFlow(false)
    override val historyLoaded: Flow<Boolean> = _historyLoaded.asStateFlow()
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
    override val newSensorFault: Flow<SensorInfo?> = _newSensorFault.asStateFlow()

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
            lastHeardMillis = 0L
            connectionFailures = 0
            connectionError = null
            sensorConnected = false
            shownBeforeFirstEvent = false
            forgetNewSensor()
        }
        _sensor.value = null
        _latest.value = null
    }

    override fun showActivatedSensor() {
        showActivatedSensor(lookAgain = true)
    }

    private fun showActivatedSensor(lookAgain: Boolean) {
        synchronized(sensorLock) {
            // The most recently activated of the active sensors (the first one when there is only
            // one, or the engine knows no start): while the previous sensor is still registered,
            // that is the one that was just activated.
            val serial = runCatching { Sensors.activeSensors().maxByOrNull { activationOf(it) } }.getOrNull()
            if (serial.isNullOrEmpty()) return
            // Already showing it, or a sensor at least as new — then no newer one was activated.
            val shown = _sensor.value
            if (shown != null && (shown.id == serial || activationOf(serial) <= shown.activationMillis)) {
                // Shown since the process start, not followed yet ([showSensorAtStart]): begin its
                // session here, as this did while that sensor had no card before its first event.
                if (shown.id == serial && sessionSerial == null) beginSessionIfNeeded(serial)
                return
            }
            // Same rule as for a connection event of the new sensor ([followsSensor]): while the
            // previous sensor is delivering it stays what the live path follows, through the new
            // one's warm-up. Switching here regardless only had its next reading switch back — the
            // card flipped new → old and the history was loaded twice. A sensor that has ended is
            // not waited for, though its last reading may be minutes old. One that was taken off
            // or is out of reach may also have just gone quiet, with [STREAMING_WINDOW_MS] not
            // over yet: look once more after it, so the new sensor's warm-up card does not have
            // to wait for that sensor's next connection event.
            val followed = sessionSerial
            val previousEnded = followed != null && followed != serial &&
                hasEnded(followed, System.currentTimeMillis())
            if (!previousEnded && !followsSensor(serial, reading = false)) {
                if (lookAgain) {
                    scope.launch {
                        delay(STREAMING_WINDOW_MS + SHOW_ACTIVATED_MARGIN_MS)
                        runCatching { showActivatedSensor(lookAgain = false) }
                    }
                }
                return
            }
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
                    val resmoothed = _history.updateAndGet { it.smoothed(on, alpha.toDouble(), GAP_THRESHOLD_MS) }
                    // Reseed the live EMA so the next appendPoint continues from the new trace.
                    liveEma = resmoothed.lastOrNull()
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
            // One sensor at a time: a reading of a second, still active sensor that the live path
            // does not follow (see followsSensor) is left out of it altogether — current value,
            // alarms, sensor card, session tracking and the live trace. The engine keeps it in that
            // sensor's own store.
            if (!serial.isNullOrEmpty() && !synchronized(sensorLock) { followsSensor(serial, reading = true) }) {
                return@setListener
            }
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
                    sensorId = serial?.takeIf { it.isNotEmpty() },
                )
            }
            // A reading proves the handshake succeeded and data is flowing: clear any connection
            // error and mark the session live before refreshing the banner.
            synchronized(sensorLock) {
                if (!serial.isNullOrEmpty()) beginSessionIfNeeded(serial)
                connectionError = null
                connectionFailures = 0
                lastHeardMillis = System.currentTimeMillis()
                if (isLive) lastReadingMillis = lastHeardMillis
                updateSensor(serial, alarm)
                if (newSensorSetup.serial != null) forgetNewSensorUnlessActive()
            }
            // Evaluate glucose alarms against the LIVE reading only (a backfilled out-of-range
            // point must not alarm). Pass the reading's own timestamp so the watch's alarm
            // controller can track signal-loss by reading time.
            if (isLive) runCatching { onReading(mgdl.toDouble(), timeMillis) }
            // Accumulate a rolling window of points so the graph fills as readings arrive (backfill
            // included). Pre-existing history is loaded separately via loadHistory().
            // In whole seconds, which is how the engine's store keeps the same reading: with the
            // callback's milliseconds the point sat a fraction of a second after its stored twin,
            // outside the span a reload replaces, and stayed beside it as a duplicate row (list,
            // CSV export) until the next reload.
            appendPoint(GlucosePoint(timeMillis / 1000 * 1000, mgdl.toDouble(), mgdl.toDouble()))
        }
        // Reflect BLE connection state into _sensor. On connect we refresh the card (so it shows
        // up during warm-up, before any reading). On disconnect we decide whether this is a
        // handshake failure worth surfacing (upstream issue #405) vs. a benign drop.
        Sensors.setStateListener { serial, connected, status, detail ->
            if (serial.isNullOrEmpty()) return@setStateListener
            var reconnected = false
            synchronized(sensorLock) {
                // The connection of a second sensor the live path does not follow is not ours to
                // report: it would swap the card and reset the followed sensor's session. Only a
                // failing setup of a newly activated one is noted, beside the card.
                if (!followsSensor(serial, reading = false)) {
                    noteUnfollowedConnection(serial, connected, status, detail)
                    return@setStateListener
                }
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
        // The sensor the engine has in use, before it reports: with one active sensor its card;
        // with two, which of them the live path follows. Before the history load below, which
        // then is the one load a newly published card asks for.
        runCatching { followNewestSensorAtStart() }
        // Load the sensor's full STORED history (5-minute readings since activation) so the
        // chart/calendar/export show real back-data, not just readings since app launch.
        runCatching { loadHistory() }
        // Begin background scanning/connecting for activated sensors (must come after start()).
        runCatching { Sensors.start() }
    }

    /**
     * With more than one sensor active when the process starts, follow the most recently activated
     * one from the start if its warm-up is over — rather than whichever reports first: a previous
     * sensor that still transmits within reach would otherwise put its value and alarms on the
     * live path until the current one's first reading. Whether an earlier-activated sensor may
     * take the path from it again is [followsSensor]'s to say (not if it has ever delivered). Does
     * nothing while the newest one is still warming up (the previous one then stays on screen as
     * itself). A single active sensor is only shown, not followed: [showSensorAtStart].
     */
    private fun followNewestSensorAtStart() {
        if (!Engine.isStarted()) return
        synchronized(sensorLock) {
            if (sessionSerial != null) return
            val active = Sensors.activeSensors()?.filterNot { it.isNullOrEmpty() } ?: return
            if (active.size < 2) {
                active.singleOrNull()?.let { showSensorAtStart(it) }
                return
            }
            // Only on the engine's word: every start known, so "the newest" is not a guess.
            if (active.any { activationOf(it) <= 0L }) return
            val newest = active.maxByOrNull { activationOf(it) } ?: return
            // Warm-up over when the process started: what [followsSensor] goes by, too.
            if (createdMillis < activationOf(newest) + SensorInfo.WARMUP_MS) return
            if (hasEnded(newest, System.currentTimeMillis())) return
            beginSessionIfNeeded(newest)
            // Its card, so that it is visible which sensor is being waited for.
            updateSensor(newest, alarm = 0)
            shownBeforeFirstEvent = _sensor.value?.id == newest
        }
    }

    /**
     * The one sensor the engine has in use when the process starts: publish its card right away,
     * built as a connection event builds it ([updateSensor]). The card used to appear only with the
     * sensor's first connection or reading — with Bluetooth off or the sensor out of reach never —
     * so until then the apps showed "no sensor" and offered no way to deactivate it, while the
     * signal-loss alarm, which asks the engine, rang for it.
     *
     * Only the card: no reading is made up, and the sensor is neither followed ([sessionSerial])
     * nor heard ([lastHeardMillis]) — which sensor the live path follows is decided by the first
     * report, exactly as without the card. Not for a sensor whose start the engine does not know
     * (the card needs it), nor for one that has ended or whose wear time has been over for more
     * than a day: the engine lists a sensor for up to two hours after its last data whatever its
     * state, and a finished one is not "the sensor". Must be called holding [sensorLock].
     */
    private fun showSensorAtStart(serial: String) {
        if (_sensor.value != null) return
        if (activationOf(serial) <= 0L) return
        if (hasEnded(serial, System.currentTimeMillis() - DAY_MS)) return
        updateSensor(serial, alarm = 0)
        shownBeforeFirstEvent = _sensor.value?.id == serial
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
     * [additions] is a canonical record for a time range — the engine store ([loadHistory]) or one
     * appended point (a mirror-peer catch-up batch is not: it only fills gaps, [mergeBackfill]). Over
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
                    val smoothedPoints = points.smoothed(
                        prefs.smoothing.value, prefs.smoothingAlpha.value.toDouble(), GAP_THRESHOLD_MS,
                    )
                    liveEma = smoothedPoints.last()
                    _history.update { cur ->
                        // The freshly re-smoothed store is authoritative over the span it covers;
                        // live-only points newer than the store snapshot are kept as the tail.
                        // Points we hold inside a hole of the store survive too: the peer's
                        // catch-up readings ([mergeBackfill]) are not in this device's store, and
                        // without this every reload reopened the gap they had filled.
                        capTail(mergeCapped(cur, smoothedPoints).filledFrom(cur, FILL_MIN_DISTANCE_MS))
                    }
                }
                // The store has been read (see GlucoseRepository.historyLoaded) — if there was an
                // engine to read it from: asked before its start it answers with nothing, which
                // is not what the store holds.
                if (Engine.isStarted()) _historyLoaded.value = true
                // Same trigger as the history (start, invalidate, new sensor): refresh the list of
                // stored sensors so past calendar days can name the sensor that covered them.
                // Lifetime (14 vs 15 days) from the engine's official end where it knows one; the
                // last stored reading is when the sensor demonstrably stopped (the calendar
                // ignores it for the newest sensor, which may still be worn) — and, for
                // [followsSensor], the proof that the sensor has delivered at all.
                _sensorHistory.value = Engine.storedSensors()
                    .map {
                        val info = SensorInfo(
                            id = it.serial,
                            activationMillis = it.startMillis,
                            endMillis = it.lastReadingMillis.takeIf { last -> last > it.startMillis },
                        )
                        lifetimeDays(it.startMillis, it.officialEndMillis)
                            ?.let { days -> info.copy(lifetimeDays = days) } ?: info
                    }
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
     * The batch only FILLS: a reading is taken where we hold none within [FILL_MIN_DISTANCE_MS], and
     * nothing we already hold is dropped or replaced. (Treated as authoritative over its span, as
     * the store is in [mergeCapped], a batch with a hole of its own — the peer was the one out of
     * reach — wiped the readings this device had measured in that hole.) The distance keeps the
     * peer's copy of a reading we already have from landing next to ours as a near-duplicate. Same
     * CAS-safe [MutableStateFlow.update] path as [appendPoint]; the filled points are in memory
     * only, and [loadHistory] keeps them as long as the store has nothing there.
     */
    override fun mergeBackfill(points: List<Pair<Long, Double>>): Int {
        if (points.isEmpty()) return 0
        val incoming = points.toMap() // one point per timestamp, the last one sent wins
            .map { (t, mgdl) -> GlucosePoint(epochMillis = t, rawMgdl = mgdl, calibratedMgdl = mgdl) }
            .sortedBy { it.epochMillis }
            .smoothed(prefs.smoothing.value, prefs.smoothingAlpha.value.toDouble(), GAP_THRESHOLD_MS)
        val before = _history.value.size
        val after = _history.updateAndGet { cur -> capTail(cur.filledFrom(incoming, FILL_MIN_DISTANCE_MS)) }
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
            lastHeardMillis = 0L
            connectionFailures = 0
            connectionError = null
            // Whichever sensor is followed now: "a new sensor beside it" starts afresh — and the
            // new one itself reports on its own card from here on.
            if (newSensorSetup.serial != null) forgetNewSensor()
        }
    }

    /**
     * A connection event of [serial], which the live path does not follow ([followsSensor] said
     * no). If it was activated after the followed sensor — the replacement, left out while the
     * previous one is still delivering — its disconnects are classified as [handleDisconnect]
     * classifies the followed sensor's, on counters of its own ([newSensorSetup]), and a failure
     * is published as [newSensorFault]: the handshake failure of a new sensor (upstream issue #405)
     * otherwise stayed unseen until the old one ended, hours later. Nothing of the live path is
     * touched — not the card, the session, nor the followed sensor's counters. An
     * earlier-activated sensor (the replaced one) or one whose order is not known is not reported
     * at all. Must be called holding [sensorLock].
     */
    private fun noteUnfollowedConnection(serial: String, connected: Boolean, status: Int, detail: String?) {
        val followed = sessionSerial ?: return
        val start = activationOf(serial)
        if (start <= activationOf(followed)) return
        _newSensorFault.value =
            newSensorSetup.onConnectionEvent(serial, start, connected, status, detail, System.currentTimeMillis())
    }

    /** Drops what is known of a new sensor beside the followed one. Must be called holding [sensorLock]. */
    private fun forgetNewSensor() {
        newSensorSetup.clear()
        _newSensorFault.value = null
    }

    /**
     * [forgetNewSensor] once the engine no longer has that sensor in use (it was finished or
     * removed): no event of it would say so. Asked with each reading of the followed sensor, and
     * only while a new sensor is being watched. Must be called holding [sensorLock].
     */
    private fun forgetNewSensorUnlessActive() {
        val watched = newSensorSetup.serial ?: return
        val active = runCatching { Sensors.activeSensors() }.getOrNull() ?: return
        if (watched !in active) forgetNewSensor()
    }

    /**
     * Whether the live path — current value, alarms, sensor card, session tracking, live trace —
     * follows [serial]. It follows ONE sensor at a time ([sessionSerial]); with a single active
     * sensor this is always true. A replacement can be activated while the previous sensor is still
     * registered and transmitting (nothing finishes it on activation); both then report, and
     * without this the value alternated between two sensors, alarms rang on the discarded one, and
     * the card, the warm-up state and a full history reload flipped with every reading.
     *
     * The followed sensor is the most recently activated one (engine start time) that has
     * delivered; until it has, the one before it stays. With no sensor followed yet, or for the
     * followed sensor itself, the answer is yes without looking further. For any other sensor
     * ([takesOverLivePath]; R = it reports a reading, C = a connection event, which is also what
     * [showActivatedSensor] counts as):
     *
     * ```
     * followed sensor is …   | reporter activated LATER | EARLIER            | order not known
     * -----------------------+--------------------------+--------------------+--------------------
     * never delivered        | R follow, C follow       | R follow, C ignore | R follow, C ignore
     * delivering             | R follow, C ignore       | ignore             | ignore
     * silent                 | R follow, C follow       | ignore             | R follow, C ignore
     * ended                  | R follow, C follow       | R follow, C ignore | R follow, C ignore
     * ```
     *
     * - never delivered: no reading of it at all — its warm-up, normally. The previous sensor, if
     *   it still delivers, takes the path back and stays on screen as itself through the new one's
     *   silent 60 minutes (and beyond, should the new one never start);
     * - delivering: a reading within [STREAMING_WINDOW_MS]. Only the first reading of a
     *   later-activated sensor ends that — from then on the previous one is left out for good;
     * - silent: it has delivered and is quiet now. An earlier-activated sensor does NOT take over
     *   then — one that was replaced and still transmits within reach would put its value and
     *   alarms on screen and keep "Signal lost" for the sensor that is worn from ever ringing;
     * - ended: not delivering, and the engine reports it ended or its wear time is over
     *   ([hasEnded]) — nothing more to wait for, so any reading takes over.
     *
     * "Has delivered" is: heard since it is followed ([lastHeardMillis]), or the store holds a
     * reading of it ([_sensorHistory] — what is left of that knowledge after a process start; the
     * watch has no such list). Until either can tell, a sensor whose warm-up was already over when
     * this process started is taken to have delivered, for the first [STREAMING_WINDOW_MS] of the
     * process — not for longer, or a new sensor that never started would shut out the one still
     * worn for good.
     *
     * "Later" is a start time greater than the followed one's, "earlier" a known one smaller than
     * it; an unknown start (0) or a tie is neither. No cell alternates: a sensor that takes over
     * by a reading is delivering from then on, and a delivering sensor yields only to the first
     * reading of a later-activated one — which, having delivered, does not yield back to an
     * earlier one. Nor does the path stay pinned to a sensor that never delivers while another one
     * does; to one that has delivered and gone silent it stays pinned against an earlier-activated
     * one on purpose, until it has ended or is deactivated ([clearActiveSensor]). Must be called
     * holding [sensorLock].
     *
     * @param reading whether [serial] reports a glucose reading (else a connection event)
     */
    private fun followsSensor(serial: String, reading: Boolean): Boolean {
        val followed = sessionSerial
        if (followed == null || followed == serial) return true
        val now = System.currentTimeMillis()
        return takesOverLivePath(
            reading = reading,
            reporterStartMillis = activationOf(serial),
            followedStartMillis = activationOf(followed),
            followedLastHeardMillis = lastHeardMillis,
            followedHasStoredReading = _sensorHistory.value.any { it.id == followed && it.endMillis != null },
            followedEnded = hasEnded(followed, now),
            processStartMillis = createdMillis,
            now = now,
            streamingWindowMillis = STREAMING_WINDOW_MS,
        )
    }

    /**
     * Whether [serial] will deliver nothing more: the engine reports it ended (state 9), or its wear
     * time is over — by the lifetime on its card where it has one, else by the engine's official
     * end, else by the default lifetime. Must be called holding [sensorLock].
     */
    private fun hasEnded(serial: String, now: Long): Boolean {
        if (Sensors.sensorState(serial) == SENSOR_STATE_ENDED) return true
        _sensor.value?.takeIf { it.id == serial }?.let { return it.isExpired(now) }
        val start = activationOf(serial).takeIf { it > 0L } ?: return false
        val officialEnd = runCatching { Sensors.sensorOfficialEndMillis(serial) }.getOrDefault(0L)
        val info = SensorInfo(id = serial, activationMillis = start)
        return (lifetimeDays(start, officialEnd)?.let { info.copy(lifetimeDays = it) } ?: info).isExpired(now)
    }

    /**
     * The engine's activation time of [serial], or the last one it reported for it (it can
     * transiently report 0 — no dataptr yet); 0 when it has never known one. Must be called holding
     * [sensorLock].
     */
    private fun activationOf(serial: String): Long {
        val start = Sensors.sensorStartMillis(serial)
        if (start > 0L) knownActivations[serial] = start
        return if (start > 0L) start else knownActivations[serial] ?: 0L
    }

    /**
     * Decides whether a disconnect is a handshake failure worth showing. Must be called holding
     * [sensorLock]. Ignores drops while data is actively flowing (transient signal loss). A
     * recorded handshake failure ([detail] non-null) is conclusive immediately; a bare error
     * status must recur [STATUS_FAIL_THRESHOLD] times so a single warm-up Bluetooth glitch is
     * not mistaken for the #405 failure. [NewSensorSetup] applies the same rules to a new sensor
     * the live path does not follow yet — keep the two in step.
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
            if (shownBeforeFirstEvent) {
                // Its first report since the card was published at the process start: the history
                // refresh that a card first published by a report gets below.
                shownBeforeFirstEvent = false
                runCatching { loadHistory() }
            }
            return
        }
        // 0 means the engine doesn't know the activation (yet) and never reported one for this
        // serial ([activationOf]): publish nothing rather than fabricate an activation — a "now"
        // fallback would invent a fresh 60-minute warm-up and "day 1/14" for an established
        // sensor. We re-enter here on every reading/connect while [_sensor] doesn't hold this
        // serial, so the card appears as soon as the engine knows.
        val activation = activationOf(serial).takeIf { it > 0L } ?: return
        val info = SensorInfo(
            id = serial,
            activationMillis = activation,
            error = error,
        )
        // Lifetime (14 vs 15 days) from the engine's official end, as for the stored sensors;
        // where the engine does not know one (0) the default stays.
        val officialEnd = runCatching { Sensors.sensorOfficialEndMillis(serial) }.getOrDefault(0L)
        _sensor.value = lifetimeDays(activation, officialEnd)?.let { info.copy(lifetimeDays = it) } ?: info
        shownBeforeFirstEvent = false
        // A newly-connected sensor brings backfilled history with it; refresh.
        runCatching { loadHistory() }
    }

    private fun appendPoint(rawPoint: GlucosePoint) {
        // Smoothed trace: a causal EMA of raw (or raw itself when smoothing is off), stored as
        // calibratedMgdl so every consumer (graph, tooltips, lists) shows it consistently.
        val point = if (prefs.smoothing.value) {
            val a = prefs.smoothingAlpha.value.toDouble()
            // Continue the EMA only from a point close in time; across a gap (warm-up, signal
            // loss, a sensor change) it starts afresh at this reading — as in [smoothed].
            val ema = liveEma
            val smoothedPoint = rawPoint.smoothedAfter(ema, _history.value, a, GAP_THRESHOLD_MS)
            // Only a reading that is not older than the running EMA moves it on. One the engine
            // replays on a reconnect is smoothed from the point before it and leaves the EMA where
            // it is — taking its place, it had the next live reading continue from a stale value.
            if (ema == null || rawPoint.epochMillis >= ema.epochMillis) liveEma = smoothedPoint
            smoothedPoint
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
        // Retain the long-term history the engine holds, not just the recent window. A Libre 3
        // stores one reading per minute (~21,600 per 15-day sensor), so this is a good year of
        // sensors — enough for the year-to-date average. The engine reloads this on each launch
        // (loadHistory), so it's a memory bound, not a data-loss cap. At ~48 bytes/point this is
        // ~29 MB worst case.
        const val MAX_POINTS = 600_000

        /** Readings within this window mean data is flowing — ignore transient drops. */
        const val STREAMING_WINDOW_MS = 6 * 60_000L

        /** Past [STREAMING_WINDOW_MS] by this much, [showActivatedSensor] looks again. */
        const val SHOW_ACTIVATED_MARGIN_MS = 5_000L

        /** Engine sensor state ([Sensors.sensorState]): the sensor reported the end of its life. */
        const val SENSOR_STATE_ENDED = 9

        /** Bare error statuses (no recorded handshake failure) must recur this many times before
         *  we alarm, so a one-off Bluetooth glitch during warm-up doesn't trip the banner. */
        const val STATUS_FAIL_THRESHOLD = 3

        /** Quiet period after the last engine invalidate before reloading history (debounce). */
        const val HISTORY_RELOAD_DEBOUNCE_MS = 1_500L

        const val DAY_MS = 24 * 60 * 60_000L

        /** A sensor's nominal lifetime in whole days, from its activation and the engine's official
         *  end; null where the engine knows no end (0), so the [SensorInfo] default stays. */
        fun lifetimeDays(startMillis: Long, officialEndMillis: Long): Int? =
            ((officialEndMillis - startMillis + DAY_MS / 2) / DAY_MS).toInt().takeIf { it > 0 }

        /** A held or catch-up point only fills a hole: it is taken where the series it is folded
         *  into has nothing within this distance. Same distance the engine uses to fill gaps of the
         *  live stream from the historic one (Engine.HIST_GAP_FILL_MS); more than half the 5-minute
         *  historic spacing, so points never interleave with a complete stored stretch. */
        const val FILL_MIN_DISTANCE_MS = 3 * 60_000L

        // --- Automatic gap-fill on reconnect ---
        /** The Libre 3's on-device historic buffer depth (what a pull can recover). */
        const val SENSOR_BUFFER_MINUTES = 8 * 60
        const val SENSOR_BUFFER_MS = SENSOR_BUFFER_MINUTES * 60_000L
        /** A gap between consecutive readings longer than this (vs. the 5-min historic spacing) is a
         *  real hole worth pulling — and one the smoothing EMA does not carry over. */
        const val GAP_THRESHOLD_MS = 15 * 60_000L
        /** Let the normal reconnect backfill + reload land before checking whether a gap remains. */
        const val AUTO_GAPFILL_SETTLE_MS = 30_000L
        /** Don't auto-pull more than once per this interval, so a flapping link can't thrash. */
        const val AUTO_GAPFILL_MIN_INTERVAL_MS = 15 * 60_000L
    }
}

/**
 * Whether a report of a sensor OTHER than the one the live path follows takes the path over — the
 * decision table at [NativeGlucoseRepository.followsSensor], as a function of plain values. Public
 * only for the unit tests (FollowedSensorTest in :app, which cannot see `internal` of this module).
 *
 * @param reading the report is a glucose reading (else a connection event)
 * @param reporterStartMillis activation of the reporting sensor, 0 = not known
 * @param followedStartMillis activation of the followed sensor, 0 = not known
 * @param followedLastHeardMillis when the followed sensor last delivered a reading since it is
 *   followed (wall clock), 0 = not yet
 * @param followedHasStoredReading the store holds a reading of the followed sensor
 * @param followedEnded the followed sensor has ended or its wear time is over
 * @param processStartMillis since when this process could have heard the followed sensor
 * @param streamingWindowMillis a reading within this window means the followed sensor delivers
 */
fun takesOverLivePath(
    reading: Boolean,
    reporterStartMillis: Long,
    followedStartMillis: Long,
    followedLastHeardMillis: Long,
    followedHasStoredReading: Boolean,
    followedEnded: Boolean,
    processStartMillis: Long,
    now: Long,
    streamingWindowMillis: Long,
): Boolean {
    val delivering = followedLastHeardMillis != 0L && now - followedLastHeardMillis <= streamingWindowMillis
    // Later-activated: its reading always; a connection event only from a sensor that is not
    // delivering (through its own warm-up the new sensor leaves a delivering one in place).
    if (reporterStartMillis > followedStartMillis) return reading || !delivering
    // Earlier-activated, or the order is not known: only by a reading, and never from a sensor
    // that delivers.
    if (!reading || delivering) return false
    if (followedEnded) return true
    val earlier = reporterStartMillis in 1 until followedStartMillis
    if (!earlier) return true
    // Earlier-activated: only while the followed sensor has never delivered.
    if (followedLastHeardMillis != 0L || followedHasStoredReading) return false
    // Not heard, and no reading of it known. Shortly after a process start that says little (the
    // store may not be read yet): a sensor whose warm-up was over by then gets the window to show.
    val dueAtProcessStart = followedStartMillis + SensorInfo.WARMUP_MS <= processStartMillis
    return !(dueAtProcessStart && now - processStartMillis <= streamingWindowMillis)
}

/**
 * Setup failures of a newly activated sensor that the live path does not follow yet (the previous
 * sensor is still delivering, see [NativeGlucoseRepository.followsSensor]) — the rules by which
 * the repository's handleDisconnect decides for the followed sensor, on counters of their own:
 * a benign disconnect (status 0 or 22 without a recorded handshake failure) counts for nothing; a
 * recorded handshake failure is conclusive at once; a bare error status is ignored during the
 * sensor's warm-up and otherwise must recur [failThreshold] times. A connect clears nothing — it
 * precedes every failed handshake; only [clear] does (the sensor delivered and is followed, the
 * followed sensor changed or was deactivated, the engine no longer has the sensor in use). Whether
 * data is flowing is not asked: a sensor that delivers is followed.
 *
 * One sensor at a time, the most recently activated: events of another one activated before it are
 * left out. Not thread-safe (the repository guards it with its sensor lock). Public only for the
 * unit tests (NewSensorSetupTest in :app, which cannot see `internal` of this module).
 */
class NewSensorSetup(private val failThreshold: Int) {
    /** The sensor being watched, or null. */
    var serial: String? = null
        private set
    private var startMillis = 0L
    private var failures = 0

    /** The watched sensor with the failure to show ([SensorInfo.error] is set), or null. */
    var fault: SensorInfo? = null
        private set

    /**
     * A connection event of [serial], activated at [startMillis] (known, and later than the
     * followed sensor's start — the caller's to check).
     *
     * @param connected true on connect, false on disconnect
     * @param status the GATT status of a disconnect
     * @param detail the failed handshake stage the engine recorded, or null
     * @return [fault] after this event
     */
    fun onConnectionEvent(
        serial: String,
        startMillis: Long,
        connected: Boolean,
        status: Int,
        detail: String?,
        now: Long,
    ): SensorInfo? {
        if (serial != this.serial) {
            if (this.serial != null && startMillis <= this.startMillis) return fault
            clear()
            this.serial = serial
            this.startMillis = startMillis
        }
        if (connected) return fault
        val error = SensorError.fromConnection(status, detail) ?: return fault
        if (detail == null && now < startMillis + SensorInfo.WARMUP_MS) return fault
        failures++
        if (detail != null || failures >= failThreshold) {
            fault = SensorInfo(id = serial, activationMillis = startMillis, error = error)
        }
        return fault
    }

    fun clear() {
        serial = null
        startMillis = 0L
        failures = 0
        fault = null
    }
}
