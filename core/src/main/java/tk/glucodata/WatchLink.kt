/*      High-level phone↔watch link facade for the redesigned apps: watch discovery,     */
/*      sensor transfer with ack + timeout + automatic revert, manual/auto take-back,    */
/*      and the watch-side bootstrap. Replaces the legacy Wearos.java dialog logic.      */
/*                                                                                       */
/*      All the heavy lifting is the legacy native mirror (netinfo/messages/backup);     */
/*      this object only sequences the same calls the legacy UI made — plus the ack      */
/*      machinery the legacy never had (it flipped BLE blindly).                         */

package tk.glucodata

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import com.google.android.gms.wearable.Node
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object WatchLink {

    private const val LOG_ID = "WatchLink"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** A paired peer running Mogluco (watch from the phone's view, phone from the watch's). */
    data class WatchNode(val id: String, val name: String)

    /** Where the sensor's BLE connection lives, as this PHONE believes it to be. */
    sealed interface TransferState {
        /** The phone owns the sensor connection (or no watch is involved). */
        data object PhoneHasSensor : TransferState

        /** Transfer sent; waiting for the watch to confirm ownership (native sBlueWatch ack
         *  or fresh watch-sourced mirror data). Reverts automatically at [deadlineMillis]. */
        data class AwaitingWatchConfirm(val nodeId: String, val deadlineMillis: Long) : TransferState

        /** The watch owns the sensor and streams values back over the mirror. */
        data class WatchHasSensor(val nodeId: String) : TransferState

        /** A transfer timed out and the phone reverted to its own connection. Cleared on
         *  the next transfer attempt (informational, so the UI can say what happened). */
        data class RevertedAfterTimeout(val nodeId: String, val atMillis: Long) : TransferState

        /** Auto-reclaim pulled the sensor back after the watch went silent. */
        data class ReclaimedFromWatch(val nodeId: String, val atMillis: Long) : TransferState
    }

    private val _nodes = MutableStateFlow<List<WatchNode>>(emptyList())
    val nodes: StateFlow<List<WatchNode>> = _nodes.asStateFlow()

    private val _transferState = MutableStateFlow<TransferState>(TransferState.PhoneHasSensor)
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()

    private var prefs: SharedPreferences? = null
    private var transferStartMillis = 0L
    private var initialized = false

    // All transfer-state transitions + the paired BLE flip go through this monitor so the deadline
    // auto-revert (poll coroutine) and a late native sBlueWatch ack (JNI thread) can't interleave
    // and leave the sensor owned by neither device (or both). Java monitors are reentrant, so a
    // native setbluetooth callback re-entering from the same thread is safe.
    private val transferLock = Any()
    // When the watch took ownership (confirm or restore). Seeds the auto-reclaim silence clock for
    // the case where the watch owns the sensor but has not yet sent any mirror data (lastwatchdata
    // still 0), so a silent-and-unreachable watch is still reclaimed.
    @Volatile private var watchOwnedSinceMillis = 0L
    // When the watch confirmed a hand-over in THIS process (0 after a restart, where the restored
    // role says nothing about when it was handed over). Lower bound for the auto-reclaim silence clock.
    @Volatile private var handOverConfirmedMillis = 0L
@Volatile private var knownNodeIds: Set<String> = emptySet()

    /** Watch: the store already held data at process start, so no /askforstart bootstrap ran. */
    @Volatile private var dataAtStart = false

    /** Last network-change-triggered /catchup that reached a peer (elapsed realtime); 0 = none yet. */
    @Volatile private var lastNetworkCatchupElapsed = 0L

    /** The one pending network-change catch-up: settling, or deferred to the end of the window. */
    private var networkCatchupJob: Job? = null

    /** False while the app runs on sample data (no engine → no mirror). */
    val isInitialized: Boolean get() = initialized

    /** Watch side: true once the phone's /start bootstrap arrived. */
    /** True once the watch is set up: settings arrived this run, or it already held data at start. */
    val initStarted: Boolean get() = Applic.initStarted || dataAtStart

    /**
     * Boot the link. Call once from Application.onCreate, AFTER Engine.start() (netinfo
     * calls need the booted engine). Symmetric: phone and watch both run this; the watch
     * additionally binds WiFi-if-configured and polls /askforstart until bootstrapped.
     */
    @JvmStatic
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        prefs = app.getSharedPreferences("watchlink", Context.MODE_PRIVATE)
        // Restore the persisted role so a phone process restart still knows the watch has
        // the sensor (the native store persists the roles; this mirrors it for the UI).
        val restored = prefs?.getString(KEY_WATCH_NODE, null)
        if (restored != null) {
            _transferState.value = TransferState.WatchHasSensor(restored)
            watchOwnedSinceMillis = System.currentTimeMillis()
        } else if (prefs?.getString(KEY_PENDING_NODE, null) != null) {
            // Recover a transfer that was awaiting confirmation when the process died: the phone's
            // BLE was turned off but the watch never confirmed, so a plain restart would leave the
            // sensor connected to neither device. Reconcile against reality once the mirror settles.
            recoverPendingTransfer()
        } else {
            selfHealBluetoothOff()
        }
        MessageSender.initwearos(app)
        registerNetworkCallback(app)
        if (Applic.isWearable) {
            if (runCatching { Natives.getWifi() }.getOrDefault(false)) UseWifi.usewifi()
            // Legacy watch bootstrap (Applic.initproc): ask the phone to init us every 30 s until
            // the /start settings arrive — but ONLY while the watch was never set up (upstream
            // gated this on `!hasData()`). Asking on every process start made the phone answer
            // with resetbylabel(remake=true), which rebuilds this watch's mirror slot as "phone
            // streams to watch" and could silently take the sensor role away from a watch that
            // owned it (plus a full re-sync and a forced-Wi-Fi /start after every restart).
            // Upstream's hasData() is also true on a pristine store (default event labels), so
            // the test here is "settings were received before" or "the store knows a sensor".
            dataAtStart = prefs?.getBoolean(KEY_BOOTSTRAPPED, false) == true ||
                runCatching { !Natives.activeSensors().isNullOrEmpty() }.getOrDefault(false)
            if (dataAtStart) {
                MessageSender.sendnetinfo()
            } else {
                scope.launch {
                    delay(4_000)
                    while (isActive && !Applic.initStarted) {
                        MessageSender.sendaskforstart()
                        delay(30_000)
                    }
                    MessageSender.sendnetinfo()
                }
            }
        } else {
            MessageSender.sendnetinfo()
        }
    }

    /** Legacy Applic.initialize(): keep the mirror alive across connectivity changes. */
    private fun registerNetworkCallback(app: Context) {
        runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            cm.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.i(LOG_ID, "network onAvailable")
                        Natives.networkpresent()
                        MessageSender.reinit()
                        MessageSender.sendnetinfo()
                        Applic.scheduler.schedule(
                            { Applic.resetWearOS() }, 20, TimeUnit.SECONDS,
                        )
                        Applic.wakemirrors()
                        // Connectivity came back — pull any readings that arrived on the peer while
                        // we were offline (the mirror stream only resumes from "now" going forward).
                        scheduleNetworkCatchup()
                    }

                    override fun onLost(network: Network) {
                        Log.i(LOG_ID, "network onLost")
                        Natives.networkabsent()
                        MessageSender.reinit()
                        Applic.wakemirrors()
                    }
                },
            )
        }.onFailure { Log.stack(LOG_ID, "registerNetworkCallback", it) }
    }

    /**
     * Network-change catch-up, at most one per [NETWORK_CATCHUP_MIN_INTERVAL_MS]: every
     * Wi-Fi/cellular/proxy handover lands in onAvailable, and each catch-up makes the peer walk and
     * ship up to a day of history. A trigger inside the window is not dropped but deferred to the
     * window's end — a link that silently dropped and came back within the window (no new node, so
     * no [onNodesChanged] catch-up either) would otherwise keep its gap until some later
     * connectivity event. The window only starts once a catch-up actually reached a peer, so an
     * attempt with nobody reachable doesn't use it up. Delayed first so the mirror re-handshake in
     * onAvailable settles. A real reconnect after a peer drop is still covered unthrottled by
     * onNodesChanged and the manual Re-sync.
     */
    private fun scheduleNetworkCatchup() {
        // One pending job, re-armed by every trigger (connectivity callbacks are serialized): the
        // window is measured in elapsed realtime, which keeps running while the device sleeps, but
        // delay() only counts while the CPU is up — a later trigger must not wait on a stale timer.
        networkCatchupJob?.cancel()
        networkCatchupJob = scope.launch {
            delay(NETWORK_CATCHUP_SETTLE_MS)
            while (true) {
                val last = lastNetworkCatchupElapsed
                if (last == 0L) break
                val left = last + NETWORK_CATCHUP_MIN_INTERVAL_MS - android.os.SystemClock.elapsedRealtime()
                if (left <= 0L) break
                delay(left)
            }
            if (runCatching { MessageSender.sendCatchupResult() }.getOrDefault(false)) {
                lastNetworkCatchupElapsed = android.os.SystemClock.elapsedRealtime()
            }
        }
    }

    /** Force a mirror re-handshake (the "Sync/Re-init" button). */
    fun reinit() {
        MessageSender.reinit()
        Applic.wakemirrors()
        // Also pull anything the peer holds that we missed. The automatic /catchup in
        // [onNodesChanged] only fires when a node *newly* appears — after a silent BT drop that
        // never removed the node from the capability set, a reconnect leaves a gap with no catch-up.
        // Re-sync is the user's explicit "back-sync now", so always ask.
        scope.launch { runCatching { MessageSender.sendCatchup() } }
    }

    /**
     * Phone: bootstrap the watch app (reset its mirror slot + send settings over /start). Reports
     * back through [onResult] once the Data-Layer send completes (or fails), so the UI can show a
     * real success/failure toast instead of firing blind.
     */
    fun sendInitWatchApp(nodeId: String, onResult: (ok: Boolean, message: String) -> Unit = { _, _ -> }) {
        scope.launch {
            val ok = runCatching {
                Natives.resetbylabel(nodeId, true)
                MessageSender.getMessageSender()?.startWearOSActivityResult(nodeId) ?: false
            }.onFailure { Log.stack(LOG_ID, "sendInitWatchApp", it) }.getOrDefault(false)
            onResult(
                ok,
                if (ok) {
                    "Settings sent — the watch will sync shortly"
                } else {
                    "Couldn't reach the watch app. Open Mogluco on the watch and keep Bluetooth on, then retry."
                },
            )
        }
    }

    // --- additive: phone→watch display-pref push + bidirectional alarm-dismiss ---
    private var uiPrefsListener: ((unitMmol: Boolean, lowMgdl: Float, highMgdl: Float) -> Unit)? = null
    private var alarmDismissListener: (() -> Unit)? = null

    /** Watch registers this to apply the display prefs (unit + target band) the phone pushes. */
    @JvmStatic
    fun setUiPrefsListener(listener: (unitMmol: Boolean, lowMgdl: Float, highMgdl: Float) -> Unit) {
        uiPrefsListener = listener
    }

    /** Either side registers this to silence its own alarm when the peer reports a dismiss. */
    @JvmStatic
    fun setAlarmDismissListener(listener: () -> Unit) {
        alarmDismissListener = listener
    }

    /** Phone: push the current display prefs (unit + target band in mg/dL) to the watch. */
    fun pushUiPrefs(unitMmol: Boolean, lowMgdl: Float, highMgdl: Float) {
        MessageSender.sendUiPrefs(unitMmol, lowMgdl, highMgdl)
    }

    /** Tell the peer an alarm was dismissed here, so it silences its matching alarm. */
    fun pushAlarmDismiss() {
        MessageSender.sendAlarmDismiss()
    }

    internal fun onUiPrefsReceived(unitMmol: Boolean, lowMgdl: Float, highMgdl: Float) {
        uiPrefsListener?.invoke(unitMmol, lowMgdl, highMgdl)
    }

    internal fun onAlarmDismissReceived() {
        alarmDismissListener?.invoke()
    }

    // --- additive: reconnect backfill (a peer re-sends readings we missed while disconnected) ---
    private var historyListener: ((points: List<Pair<Long, Double>>) -> Unit)? = null

    /** Either side registers this to merge a peer's catch-up history into its own store. */
    @JvmStatic
    fun setHistoryListener(listener: (List<Pair<Long, Double>>) -> Unit) {
        historyListener = listener
    }

    // Emits the number of NEW readings merged each time a catch-up /history batch is applied, so the
    // watch's "Re-sync" can report a real outcome (filled N / already up to date) instead of firing
    // blind. extraBufferCapacity lets tryEmit succeed without an active collector.
    private val _catchupEvents = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val catchupEvents: kotlinx.coroutines.flow.SharedFlow<Int> = _catchupEvents.asSharedFlow()

    /** The history listener reports the count of newly-merged points here (see WearApp wiring). */
    @JvmStatic
    fun notifyCatchupMerged(newPoints: Int) {
        _catchupEvents.tryEmit(newPoints)
    }

    // --- additive: phone → watch alarm-timing config ---
    private var wearAlarmListener: ((repeatMin: Int, snoozeMin: Int, timeoutSec: Int) -> Unit)? = null

    /** Watch registers this to apply alarm timings pushed from the phone. */
    @JvmStatic
    fun setWearAlarmListener(listener: (Int, Int, Int) -> Unit) {
        wearAlarmListener = listener
    }

    /** Phone: the timings last pushed, kept so [resendWearAlarm] can repeat them. */
    @Volatile private var lastWearAlarm: Triple<Int, Int, Int>? = null

    /** Phone: push the watch's alarm timings (repeat/snooze minutes, timeout seconds). */
    fun pushWearAlarm(repeatMin: Int, snoozeMin: Int, timeoutSec: Int) {
        lastWearAlarm = Triple(repeatMin, snoozeMin, timeoutSec)
        MessageSender.sendWearAlarm(repeatMin, snoozeMin, timeoutSec)
    }

    /**
     * Phone: the watch asked for a catch-up, i.e. it just (re)gained the link from its own point of
     * view — repeat the alarm timings too. The app pushes them when a watch appears in the node set
     * and on every change, but that send is fire-and-forget: one lost to a link that looked up but
     * wasn't would leave the watch on older timings (e.g. a shorter alarm duration than the phone
     * shows) until the next change. Applying equal values on the watch is a no-op.
     */
    internal fun resendWearAlarm() {
        val (repeatMin, snoozeMin, timeoutSec) = lastWearAlarm ?: return
        MessageSender.sendWearAlarm(repeatMin, snoozeMin, timeoutSec)
    }

    internal fun onWearAlarmReceived(repeatMin: Int, snoozeMin: Int, timeoutSec: Int) {
        wearAlarmListener?.invoke(repeatMin, snoozeMin, timeoutSec)
    }

    /**
     * Build the recent-history payload answering a peer's /catchup: every stored reading in the last
     * [CATCHUP_WINDOW_MS], packed as count + (long time, int mg/dL)*. Empty when there's nothing.
     */
    fun buildCatchupPayload(): ByteArray {
        val flat = runCatching { Engine.glucoseHistory() }.getOrNull() ?: return ByteArray(0)
        val since = System.currentTimeMillis() - CATCHUP_WINDOW_MS
        val times = ArrayList<Long>()
        val values = ArrayList<Int>()
        var i = 0
        while (i + 1 < flat.size) {
            val t = flat[i]
            if (t >= since) { times.add(t); values.add(flat[i + 1].toInt()) }
            i += 2
        }
        if (times.isEmpty()) return ByteArray(0)
        // If the window holds more than the cap (dense 1-minute data over a full day), keep the most
        // recent [CATCHUP_MAX_POINTS] so the message stays under the Data-Layer size limit.
        val from = (times.size - CATCHUP_MAX_POINTS).coerceAtLeast(0)
        val count = times.size - from
        val buf = java.nio.ByteBuffer.allocate(4 + count * 12)
        buf.putInt(count)
        for (j in from until times.size) { buf.putLong(times[j]); buf.putInt(values[j]) }
        return buf.array()
    }

    /** Parse a /history batch and hand the points to the registered listener (which dedupes). */
    internal fun onHistoryReceived(payload: ByteArray) {
        if (payload.size < 4) return
        val buf = java.nio.ByteBuffer.wrap(payload)
        val n = buf.int
        val out = ArrayList<Pair<Long, Double>>(n.coerceIn(0, 10_000))
        var k = 0
        while (k < n && buf.remaining() >= 12) {
            out.add(buf.long to buf.int.toDouble())
            k++
        }
        if (out.isNotEmpty()) historyListener?.invoke(out)
    }

    /**
     * Phone: hand the sensor's BLE connection to the watch (legacy Wearos "Direct
     * sensor-watch connection" = checked), then wait for the watch to confirm — via the
     * native sBlueWatch ack ([onNativeSwitch]) or fresh watch-sourced mirror data. If no
     * confirmation arrives before [timeoutMillis], automatically revert so a failed
     * transfer always leaves the sensor connected to the phone.
     */
    fun requestTransferToWatch(nodeId: String, timeoutMillis: Long = DEFAULT_TRANSFER_TIMEOUT_MS) {
        scope.launch {
            val sender = MessageSender.getMessageSender()
            if (sender == null) {
                Log.e(LOG_ID, "transfer: no MessageSender")
                return@launch
            }
            val netinfo = runCatching { Natives.getmynetinfo(nodeId, true, 1, true, 0) }.getOrNull()
            if (netinfo == null) {
                Log.e(LOG_ID, "transfer: getmynetinfo=null")
                return@launch
            }
            // A new hand-off supersedes any earlier reclaim. The persisted "order the watch's BLE
            // off when it reappears" marker from a previous take-back/timeout must not survive into
            // this transfer — otherwise the next Data-Layer node event (any brief watch↔phone BT
            // drop) sends /bluetooth off to the watch that now owns the sensor, and readings stop
            // with the phone still believing the watch has them.
            clearNeedsBluetoothOff()
            transferStartMillis = System.currentTimeMillis()
            val deadline = transferStartMillis + timeoutMillis
            _transferState.value = TransferState.AwaitingWatchConfirm(nodeId, deadline)
            // Persist the in-flight transfer so a process death before confirmation is reconciled
            // on next boot (recoverPendingTransfer) instead of stranding the sensor off the phone.
            persistPendingTransfer(nodeId, deadline)
            sender.sendnetinfo(nodeId, netinfo)
            sender.sendbluetooth(nodeId, true)
            Applic.setbluetooth(false)
            awaitWatchConfirm(nodeId, deadline, transferStartMillis)
        }
    }

    /**
     * Poll until the watch confirms ownership (the native ack usually beats this) or the deadline
     * passes, then revert. The revert is taken under [transferLock] and only while we're still
     * awaiting confirmation for [nodeId], so a concurrent native ack and this timeout can't both
     * act — whichever takes the lock first flips the state and makes the other a no-op.
     */
    private suspend fun awaitWatchConfirm(nodeId: String, deadline: Long, startMillis: Long) {
        // What this poll proves is weaker than "the watch has the sensor": directsensorwatch is the
        // phone's own role bit (set by the hand-over itself) and lastwatchdata is the mirror's
        // up-to-date stamp, which the phone's own sync pass to the watch sets as well — together
        // "the mirror to that watch worked since the hand-over". The native sBlueWatch ack never
        // fires (nothing calls Natives.watchBluetooth). Deliberately not tightened to "a reading
        // newer than startMillis": a watch that needs longer than the window to connect and deliver
        // its first reading would be reverted mid-connect, with both devices after the sensor.
        // This is a plain suspend fun (no CoroutineScope receiver), so cooperative-cancellation
        // uses the coroutine context's isActive rather than the CoroutineScope extension.
        while (coroutineContext.isActive) {
            delay(15_000)
            val st = _transferState.value
            if (st !is TransferState.AwaitingWatchConfirm || st.nodeId != nodeId) return
            val confirmed = runCatching {
                Natives.directsensorwatch(nodeId) == 1 &&
                    Natives.lastwatchdata(nodeId) > startMillis
            }.getOrDefault(false)
            if (confirmed) {
                confirmWatchHasSensor(nodeId)
                return
            }
            if (System.currentTimeMillis() >= deadline) {
                synchronized(transferLock) {
                    val cur = _transferState.value
                    if (cur is TransferState.AwaitingWatchConfirm && cur.nodeId == nodeId) {
                        Log.e(LOG_ID, "transfer to $nodeId timed out — reverting to phone")
                        performTakeBack(nodeId)
                        _transferState.value =
                            TransferState.RevertedAfterTimeout(nodeId, System.currentTimeMillis())
                    }
                }
                return
            }
        }
    }

    /** Phone: reclaim the sensor (manual button, watch /takeback request, or auto-reclaim). */
    fun takeBackSensor(nodeId: String) {
        scope.launch {
            synchronized(transferLock) {
                performTakeBack(nodeId)
                _transferState.value = TransferState.PhoneHasSensor
            }
            // The phone owns the BLE link again; nudge the store to re-read once the sensor's own
            // historic buffer has been pulled in over the reconnect (see scheduleBackfillReloads).
            scheduleBackfillReloads()
        }
    }

    /**
     * After the phone re-owns the BLE link on reclaim, the Libre 3's own historic buffer is pulled
     * in over the reconnect (Libre3GattCallback.fillHistory) — but asynchronously, over several
     * seconds. Nudge the engine's invalidate a few times so the repository re-reads the store once
     * that backfill has landed; otherwise the phone keeps showing a gap for the out-of-range window
     * even though the missed readings are now stored. Read-only: it only triggers the debounced
     * history reload ([Engine.addInvalidateListener]); nothing touches the native write path.
     */
    private fun scheduleBackfillReloads() {
        scope.launch {
            for (delayMs in longArrayOf(8_000, 15_000, 30_000)) {
                delay(delayMs)
                runCatching { Engine.dispatchInvalidate() }
            }
        }
    }

    /**
     * Tell the peer watch to release the sensor: netinfo with watchHasSensor=-1 (role flip)
     * + bluetooth-off. Best-effort — an unreachable watch simply won't receive it (it gets
     * re-sent via [onNodesChanged] when it reappears after an auto-reclaim).
     */
    private fun sendReleaseToPeer(nodeId: String) {
        val sender = MessageSender.getMessageSender() ?: return
        val netinfo = Natives.getmynetinfo(nodeId, true, -1, true, 0)
        if (netinfo != null) sender.sendnetinfo(nodeId, netinfo)
        sender.sendbluetooth(nodeId, false)
    }

    private fun performTakeBack(nodeId: String) {
        runCatching {
            sendReleaseToPeer(nodeId)
            // The release above is best-effort — an unreachable watch (dead battery, out of
            // range) misses it and still believes it owns the sensor when it comes back. Persist
            // the marker so [onNodesChanged] re-orders it off on reappearance, for EVERY
            // take-back path (manual button, /takeback, auto-reclaim, restart-recovery) — not
            // just auto-reclaim. Harmless when the watch did receive it (idempotent role flip).
            prefs?.edit()?.putString(KEY_NEEDS_BT_OFF, nodeId)?.apply()
            Applic.setbluetooth(true)
            persistWatchNode(null)
            clearPendingTransfer()
        }.onFailure { Log.stack(LOG_ID, "performTakeBack", it) }
    }

    /**
     * Watch: ask the phone to take the sensor back (settings "Hand sensor back"). Returns whether
     * the request was actually dispatched to a reachable phone, so the UI can tell the user it was
     * sent vs. that no phone is reachable yet (instead of firing blind). When no node is cached yet,
     * kicks off a rediscovery so a prompt retry can succeed.
     */
    fun requestHandBack(): Boolean {
        val sender = MessageSender.getMessageSender() ?: return false
        val peers = sender.nodes
        if (peers.isNullOrEmpty()) {
            Log.e(LOG_ID, "requestHandBack: no phone node reachable")
            scope.launch { runCatching { sender.findWearDevicesWithApp() } }
            return false
        }
        sender.sendtakeback(peers.elementAt(0).id)
        return true
    }

    /** Epoch millis of the last mirror data received from the peer, or 0. */
    fun lastWatchDataMillis(nodeId: String): Long =
        runCatching { Natives.lastwatchdata(peerHostName(nodeId)) }.getOrDefault(0L)

    /** True when the native store says the watch currently owns the sensor stream. */
    fun watchOwnsSensor(nodeId: String): Boolean =
        runCatching { Natives.directsensorwatch(nodeId) == 1 }.getOrDefault(false)

    /** On the watch the mirror host is keyed by our own node id; on the phone by the peer's. */
    private fun peerHostName(nodeId: String): String {
        if (!Applic.isWearable) return nodeId
        return MessageSender.getMessageSender()?.localnode ?: nodeId
    }

    /**
     * Phone: opt-in auto-reclaim. When the watch owns the sensor and has been silent AND
     * unreachable for [minutes], pull the connection back so readings resume on the phone.
     * When the watch reappears it is immediately ordered off (see [onNodesChanged]).
     */
    fun startAutoReclaimMonitor(
        enabled: StateFlow<Boolean>,
        minutes: StateFlow<Int>,
        // Epoch millis of the last reading actually received (the same value the "Last data from
        // watch" row shows). Preferred over the coarser mirror sync marker so the silence clock
        // matches what the user sees. Returns 0 when unknown.
        lastReadingMillis: () -> Long = { 0L },
        onReclaimed: (nodeId: String) -> Unit = {},
    ) {
        scope.launch {
            while (isActive) {
                // Park (no wakeups) until auto-reclaim is armed: enabled AND the watch owns
                // the sensor. The common case — phone owns the sensor / toggle off — costs
                // nothing.
                combineArmed(enabled).first { it }
                // Armed: poll once a minute while it stays armed.
                while (isActive && enabled.value) {
                    val st = _transferState.value as? TransferState.WatchHasSensor ?: break
                    val silenceMs = minutes.value.coerceAtLeast(MIN_RECLAIM_MINUTES) * 60_000L
                    // Prefer the actual last-received-reading time (same source the "Last data from
                    // watch" row shows) over the coarser mirror sync marker; fall back to that marker,
                    // then to when the watch took ownership — so a watch that never sent any reading
                    // (grabbed the sensor via the native ack, then went silent) is still reclaimed
                    // instead of leaving the phone's BLE off forever with the `last != 0L` gate unmet.
                    val reading = lastReadingMillis()
                    val last = if (reading != 0L) reading else lastWatchDataMillis(st.nodeId)
                    // The silence clock never starts before the hand-over itself: the phone's last
                    // reading may predate it by more than the window (that can be why the sensor was
                    // sent over), and the watch must get the whole window to deliver its first one —
                    // otherwise a brief drop right after the hand-over reclaims at once.
                    val since = maxOf(if (last != 0L) last else watchOwnedSinceMillis, handOverConfirmedMillis)
                    val silentFor = System.currentTimeMillis() - since
                    val reachable = _nodes.value.any { it.id == st.nodeId }
                    if (since != 0L && silentFor > silenceMs && !reachable) {
                        Log.e(LOG_ID, "auto-reclaim: watch silent ${silentFor / 60000} min — taking sensor back")
                        synchronized(transferLock) {
                            performTakeBack(st.nodeId) // also persists the needs-bluetooth-off marker
                            _transferState.value =
                                TransferState.ReclaimedFromWatch(st.nodeId, System.currentTimeMillis())
                        }
                        onReclaimed(st.nodeId)
                        break
                    }
                    delay(60_000)
                }
            }
        }
    }

    /** True while auto-reclaim should poll: toggle on AND the watch owns the sensor. */
    private fun combineArmed(enabled: StateFlow<Boolean>) =
        kotlinx.coroutines.flow.combine(enabled, transferState) { e, st ->
            e && st is TransferState.WatchHasSensor
        }

    // --- internal hooks ---

    /** From MessageSender when the capability set changes. */
    internal fun onNodesChanged(ns: Set<Node>) {
        _nodes.value = ns.map { WatchNode(it.id, it.displayName) }
        // A peer (re)appeared — ask it to re-send any readings we missed while disconnected.
        val ids = ns.map { it.id }.toSet()
        val appeared = ids - knownNodeIds
        knownNodeIds = ids
        if (appeared.isNotEmpty()) {
            scope.launch { runCatching { MessageSender.sendCatchup() } }
        }
        // A reclaimed-from watch just reappeared: order its BLE off before it can compete
        // with the phone for the sensor connection. Never while the watch is (becoming) the
        // owner — a stale marker must not cut off the device that is streaming right now.
        val needsOff = prefs?.getString(KEY_NEEDS_BT_OFF, null) ?: return
        val st = _transferState.value
        val ownerNode = when (st) {
            is TransferState.WatchHasSensor -> st.nodeId
            is TransferState.AwaitingWatchConfirm -> st.nodeId
            else -> null
        }
        // Only when the marker names the very watch that now (re)owns the sensor; a marker for a
        // DIFFERENT, reclaimed-from watch must still be delivered when that watch reappears.
        if (ownerNode != null && ownerNode == needsOff) {
            Log.i(LOG_ID, "dropping stale bluetooth-off marker for $needsOff — that watch owns the sensor")
            clearNeedsBluetoothOff()
            return
        }
        if (ns.any { it.id == needsOff }) {
            Log.i(LOG_ID, "reappeared after reclaim — sending bluetooth-off to $needsOff")
            scope.launch {
                runCatching {
                    // Only forget the marker once the release was really handed to the watch: a
                    // node can be listed while not actually connected, and a marker dropped after a
                    // send that went nowhere leaves that watch believing it owns the sensor for good.
                    if (deliverReleaseToPeer(needsOff)) {
                        if (prefs?.getString(KEY_NEEDS_BT_OFF, null) == needsOff) clearNeedsBluetoothOff()
                    } else if (stillNeedsRelease(needsOff)) {
                        Log.e(LOG_ID, "bluetooth-off to $needsOff not delivered — keeping the marker")
                    }
                }.onFailure { Log.stack(LOG_ID, "post-reclaim bluetooth-off", it) }
            }
        }
    }

    /** True while the persisted marker still orders [nodeId] off and that watch is not the owner. */
    private fun stillNeedsRelease(nodeId: String): Boolean {
        if (prefs?.getString(KEY_NEEDS_BT_OFF, null) != nodeId) return false
        return when (val st = _transferState.value) {
            is TransferState.WatchHasSensor -> st.nodeId != nodeId
            is TransferState.AwaitingWatchConfirm -> st.nodeId != nodeId
            else -> true
        }
    }

    /**
     * [sendReleaseToPeer] for the marker re-send — the same two messages, but awaiting the
     * Data-Layer sends: true when both the role flip and the bluetooth-off were handed to a
     * connected watch. Blocking (up to the send timeout each). Sends nothing once a new hand-over
     * to that watch superseded the marker.
     */
    private fun deliverReleaseToPeer(nodeId: String): Boolean {
        val sender = MessageSender.getMessageSender() ?: return false
        if (!stillNeedsRelease(nodeId)) return false
        val netinfo = Natives.getmynetinfo(nodeId, true, -1, true, 0)
        val flipped = netinfo != null && sender.sendnetinfoResult(nodeId, netinfo)
        // The first send can block for a while on a flaky link; never order the watch's BLE off
        // after the user handed the sensor to it in the meantime.
        if (!stillNeedsRelease(nodeId)) return false
        return sender.sendbluetoothResult(nodeId, false) && flipped
    }

    /** Watch: the phone's /start settings arrived — later process starts skip the bootstrap. */
    internal fun markBootstrapped() {
        prefs?.edit()?.putBoolean(KEY_BOOTSTRAPPED, true)?.apply()
    }

    private fun clearNeedsBluetoothOff() {
        prefs?.edit()?.remove(KEY_NEEDS_BT_OFF)?.apply()
    }

    /** From Applic.switchbluetooth (Java) — the native sBlueWatch ack (watch announced
     *  ownership). Not for app use. */
    @JvmStatic
    fun onNativeSwitch(name: String, watchHasBluetooth: Boolean) {
        if (watchHasBluetooth) confirmWatchHasSensor(name)
    }

    /** From Applic.setbluetooth (Java). Not for app use. */
    @JvmStatic
    fun onBluetoothChanged(on: Boolean) {
        if (!on) return
        synchronized(transferLock) {
            if (_transferState.value is TransferState.WatchHasSensor) {
                // Our BLE came back on while we believed the watch owned the sensor (e.g. a
                // /defaults reset) — reflect reality.
                _transferState.value = TransferState.PhoneHasSensor
                persistWatchNode(null)
                clearPendingTransfer()
            }
        }
    }

    /**
     * Phone, from MessageReceiver right after a watch's /netinfo was applied natively. The native
     * handshake knows nothing of [transferState]: ANY netinfo in which the watch still claims the
     * sensor makes the phone's engine yield (usebluetooth off, no stream to that watch) — also a
     * stale one from a watch that missed the release (built before it processed it, or persisted
     * across its restart). Left alone, the phone then answers "the watch has the sensor", the watch
     * re-arms itself, and both devices go for the sensor's single BLE slot. The same flag is turned
     * off by a reading the watch still mirrored after a take-back.
     *
     * So when the flag is off although no watch is (becoming) the owner here — the state
     * [selfHealBluetoothOff] repairs at start — re-assert the phone's role and order that watch
     * off again. Never during a transfer or while a watch owns the sensor.
     */
    internal fun onPeerNetinfoApplied(nodeId: String) {
        if (Applic.isWearable || !initialized) return
        if (runCatching { Natives.getusebluetooth() }.getOrDefault(true)) return
        synchronized(transferLock) {
            // Flag first, state second: a hand-over publishes AwaitingWatchConfirm BEFORE it turns
            // the flag off, and every take-back turns it back on under this lock before changing
            // the state — so "off" seen here together with a phone-owned state is never a transfer
            // in progress.
            val off = runCatching { !Natives.getusebluetooth() }.getOrDefault(false)
            val st = _transferState.value
            if (!off || st is TransferState.WatchHasSensor || st is TransferState.AwaitingWatchConfirm) return
            Log.e(LOG_ID, "netinfo from $nodeId turned usebluetooth off while the phone owns the sensor — re-asserting")
            runCatching {
                sendReleaseToPeer(nodeId)
                // Re-sent on its next reappearance until it was really delivered (onNodesChanged).
                prefs?.edit()?.putString(KEY_NEEDS_BT_OFF, nodeId)?.apply()
                if (SensorBluetooth.blueone == null) {
                    Applic.setbluetooth(true) // BLE manager is down as well: the self-heal sequence
                } else {
                    // The manager is up (and possibly connected): only the flag was flipped. Not
                    // Applic.setbluetooth(true), which would drop and re-open a live connection.
                    Natives.setusebluetooth(true)
                }
            }.onFailure { Log.stack(LOG_ID, "onPeerNetinfoApplied", it) }
        }
    }

    private fun confirmWatchHasSensor(nodeId: String) {
        synchronized(transferLock) {
            // A stale/late ack must not undo a take-back the phone just performed (which turned the
            // phone's BLE back on): honoring it would flip us to "watch has sensor" with the watch
            // already released and nobody streaming. The revert is the documented safety outcome.
            val st = _transferState.value
            if ((st is TransferState.RevertedAfterTimeout && st.nodeId == nodeId) ||
                (st is TransferState.ReclaimedFromWatch && st.nodeId == nodeId)
            ) {
                Log.i(LOG_ID, "ignoring watch $nodeId ack — phone already reclaimed the sensor")
                return
            }
            Log.i(LOG_ID, "watch $nodeId confirmed sensor ownership")
            clearNeedsBluetoothOff()
            watchOwnedSinceMillis = System.currentTimeMillis()
            handOverConfirmedMillis = watchOwnedSinceMillis
            _transferState.value = TransferState.WatchHasSensor(nodeId)
            persistWatchNode(nodeId)
            clearPendingTransfer()
        }
    }

    /**
     * Phone: on boot, if a transfer was awaiting confirmation when we died (phone BLE off, watch
     * not yet confirmed), reconcile against reality once the mirror re-handshakes: if the watch
     * really owns the sensor now, adopt that; otherwise take the connection back so the sensor is
     * never left owned by nobody.
     */
    private fun recoverPendingTransfer() {
        val pendingNode = prefs?.getString(KEY_PENDING_NODE, null) ?: return
        val deadline = prefs?.getLong(KEY_PENDING_DEADLINE, 0L) ?: 0L
        _transferState.value = TransferState.AwaitingWatchConfirm(pendingNode, deadline)
        scope.launch {
            // Let the mirror re-handshake so directsensorwatch/lastwatchdata are meaningful.
            delay(RECOVER_SETTLE_MS)
            val owns = watchOwnsSensor(pendingNode) && lastWatchDataMillis(pendingNode) > 0L
            if (owns) {
                confirmWatchHasSensor(pendingNode)
            } else {
                synchronized(transferLock) {
                    val cur = _transferState.value
                    if (cur is TransferState.AwaitingWatchConfirm && cur.nodeId == pendingNode) {
                        Log.e(LOG_ID, "pending transfer to $pendingNode unconfirmed after restart — reclaiming")
                        performTakeBack(pendingNode)
                        _transferState.value = TransferState.PhoneHasSensor
                    }
                }
            }
        }
    }

    /**
     * Safety net (phone only): nothing records the watch owning the sensor and no transfer is
     * pending, yet the native BLE flag is off — a state where NOBODY streams. Seen when the role
     * prefs and the native flag desync (e.g. cleared app data on one side). The role says the
     * phone owns the sensor, so its BLE must be on. Delayed so the engine is booted first.
     */
    private fun selfHealBluetoothOff() {
        if (Applic.isWearable) return
        scope.launch {
            delay(RECOVER_SETTLE_MS)
            val watchOwns = prefs?.getString(KEY_WATCH_NODE, null) != null
            val pending = prefs?.getString(KEY_PENDING_NODE, null) != null
            val bleOff = runCatching { !Natives.getusebluetooth() }.getOrDefault(false)
            if (!watchOwns && !pending && bleOff) {
                Log.e(LOG_ID, "BLE off with no watch owning the sensor — turning it back on")
                Applic.setbluetooth(true)
            }
        }
    }

    /**
     * Phone: the user just activated a sensor with THIS phone's NFC. Ownership of the (new)
     * sensor belongs to the phone, whatever the previous role said — with the sensor gate in
     * [Sensors.start] an unresolved watch-owned role would otherwise leave the phone refusing
     * to scan for the sensor it just activated after its next restart. If a watch owned the old
     * sensor, it is released (and re-ordered off when it reappears, via the persisted marker).
     */
    fun onPhoneActivatedSensor() {
        if (Applic.isWearable) return
        synchronized(transferLock) {
            val st = _transferState.value
            when (st) {
                is TransferState.WatchHasSensor -> {
                    Log.i(LOG_ID, "sensor activated on the phone — reclaiming role from ${st.nodeId}")
                    performTakeBack(st.nodeId)
                }
                is TransferState.AwaitingWatchConfirm -> {
                    Log.i(LOG_ID, "sensor activated on the phone — cancelling pending transfer to ${st.nodeId}")
                    performTakeBack(st.nodeId)
                }
                else ->
                    // Already phone-owned; just make sure the native flag agrees so scanning
                    // survives the next restart.
                    if (runCatching { !Natives.getusebluetooth() }.getOrDefault(false)) {
                        Applic.setbluetooth(true)
                    }
            }
            _transferState.value = TransferState.PhoneHasSensor
        }
    }

    private fun persistWatchNode(nodeId: String?) {
        prefs?.edit()?.apply {
            if (nodeId == null) remove(KEY_WATCH_NODE) else putString(KEY_WATCH_NODE, nodeId)
            apply()
        }
    }

    private fun persistPendingTransfer(nodeId: String, deadline: Long) {
        prefs?.edit()?.putString(KEY_PENDING_NODE, nodeId)?.putLong(KEY_PENDING_DEADLINE, deadline)?.apply()
    }

    private fun clearPendingTransfer() {
        prefs?.edit()?.remove(KEY_PENDING_NODE)?.remove(KEY_PENDING_DEADLINE)?.apply()
    }

    private const val KEY_WATCH_NODE = "watch_node_id"
    private const val KEY_BOOTSTRAPPED = "watch_bootstrapped"
    private const val KEY_NEEDS_BT_OFF = "watch_needs_bt_off"
    private const val KEY_PENDING_NODE = "pending_transfer_node"
    private const val KEY_PENDING_DEADLINE = "pending_transfer_deadline"
    private const val RECOVER_SETTLE_MS = 20_000L
    // While awaiting the watch's confirmation the phone's own BLE is off, so a failed hand-off
    // means NO readings anywhere until this reverts. A real BLE takeover completes in well under a
    // minute, so keep the strand short: revert to the phone after 2 min rather than 6.
    const val DEFAULT_TRANSFER_TIMEOUT_MS = 2 * 60_000L
    // How far back a reconnect catch-up re-sends readings (the peer answers a /catchup with this).
    // A watch can be out of range for most of a day (charging overnight, left at home); 6 h left a
    // visible gap for anything longer, so cover a full day. [CATCHUP_MAX_POINTS] caps the packed
    // payload well under the Data-Layer message limit regardless of window.
    const val CATCHUP_WINDOW_MS = 24 * 60 * 60 * 1000L

    /** Minimum spacing of connectivity-triggered catch-ups (see [scheduleNetworkCatchup]). */
    private const val NETWORK_CATCHUP_MIN_INTERVAL_MS = 10 * 60_000L

    /** Wait after a connectivity change before asking, so the mirror re-handshake settles first. */
    private const val NETWORK_CATCHUP_SETTLE_MS = 8_000L

    // Cap the catch-up batch so the packed payload (4 + n*12 bytes) stays comfortably under the
    // ~100 KB Data-Layer message limit even at 1-minute resolution over the whole window.
    private const val CATCHUP_MAX_POINTS = 6000
    const val MIN_RECLAIM_MINUTES = 10
}
