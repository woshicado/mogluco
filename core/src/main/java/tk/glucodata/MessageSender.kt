/*      Modified 2026 by Joshua Meyer as part of Mogluco, a Material 3               */
/*      redesign fork of Juggluco. See the NOTICE file for details.                  */
/*                                                                                   */
/*      Phone ↔ watch Data-Layer send side. Ported from the legacy MessageSender.kt     */
/*      (GPLv3, Copyright (C) 2021 Jaap Korthals Altes) with the legacy-UI references   */
/*      removed. The class/package name and the four @Keep static methods are a JNI     */
/*      contract: libg.so's jinitmessages (watch/messages.cpp) resolves them at load    */
/*      and calls them to tunnel the mirror byte-stream through Data-Layer messages.    */

package tk.glucodata

import android.content.Context
import androidx.annotation.Keep
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import tk.glucodata.Applic.JUGGLUCOIDENT
import tk.glucodata.Applic.isWearable
import tk.glucodata.Log.doLog

class MessageSender(val activity: Context) : CapabilityClient.OnCapabilityChangedListener {
    private val messageClient by lazy { Wearable.getMessageClient(activity) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(activity) }
    private val nodeClient by lazy { Wearable.getNodeClient(activity) }
    val localnodeall: Node by lazy { Tasks.await(nodeClient.localNode) }
    val localnode: String by lazy { localnodeall.id }
    val galaxywatch by lazy { isGalaxy(localnodeall) }

    // Reassigned as a pair in setnodes() but non-atomically, and read from multiple IO-scope
    // coroutines: @Volatile guarantees each read sees a fully-published reference (never a torn
    // one). Consumers that index nexttimes by nodes must still guard the sizes match (a read can
    // straddle a setnodes() swap and capture the two fields from different generations).
    @Volatile var nodes: Set<Node>? = null
    @Volatile private var nexttimes: LongArray? = null

    private fun setnodes(ns: Set<Node>) {
        nodes = ns
        nexttimes = LongArray(ns.size)
        WatchLink.onNodesChanged(ns)
        sendnetinfo()
    }

    fun nulltimes() {
        nexttimes?.fill(0L)
    }

    private var nodesbusy = false

    suspend fun findWearDevicesWithApp() {
        Log.i(LOG_ID, "start findWearDevicesWithApp nodesbusy=$nodesbusy")
        if (nodesbusy) return
        nodesbusy = true
        try {
            val capabilityInfo =
                capabilityClient.getCapability(JUGGLUCOIDENT, CapabilityClient.FILTER_REACHABLE).await()
            setnodes(capabilityInfo.nodes)
            Log.d(LOG_ID, "Capable Nodes: $nodes")
            Natives.isGalaxyWatch(galaxywatch)
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "findDev", th)
        } finally {
            nodesbusy = false
        }
    }

    fun finddevices() {
        val sender = this
        scope.launch { findWearDevicesWithApp() }
        Wearable.getCapabilityClient(activity).addListener(sender, JUGGLUCOIDENT)
    }

    init {
        finddevices()
    }

    /** Bootstrap the watch app: ship the serialized engine settings over /start. */
    fun startWearOSActivity(nodeName: String) {
        val data = Natives.bytesettings()
        nameSendMessage(nodeName, START_PATH, data)
    }

    /** Like [startWearOSActivity] but awaits the Data-Layer send and reports whether it landed. */
    fun startWearOSActivityResult(nodeName: String): Boolean {
        val data = Natives.bytesettings()
        return nameSendMessageResult(nodeName, START_PATH, data)
    }

    fun toDefaults(nodeName: String) {
        nameSendMessage(nodeName, DEFAULTS_PATH, byteArrayOf(0))
    }

    override fun onCapabilityChanged(cap: CapabilityInfo) {
        scope.launch { setnodes(cap.nodes) }
    }

    private fun sendmessage(path: String, data: ByteArray) {
        try {
            when {
                nodes == null -> {
                    Log.d(LOG_ID, "sendmessage nodes=null")
                    scope.launch { findWearDevicesWithApp() }
                }
                nodes?.isEmpty() == true -> Log.d(LOG_ID, "sendmessage nodes.isEmpty")
                else -> nodes?.map { node ->
                    scope.launch {
                        try {
                            messageClient.sendMessage(node.id, path, data)
                        } catch (th: Throwable) {
                            Log.stack(LOG_ID, th)
                        }
                    }
                }
            }
        } catch (exception: Exception) {
            Log.d(LOG_ID, "sendmessage failed: $exception")
        }
    }

    private fun nameSendMessage(name: String, path: String, data: ByteArray) {
        scope.launch {
            try {
                messageClient.sendMessage(name, path, data)
            } catch (th: Throwable) {
                Log.stack(LOG_ID, th)
            }
        }
    }

    private fun nameSendMessageResult(name: String, path: String, data: ByteArray): Boolean {
        return try {
            val timeout: Long = 60L
            Tasks.await(messageClient.sendMessage(name, path, data), timeout, TimeUnit.SECONDS)
            true
        } catch (th: Throwable) {
            Log.stack(LOG_ID, th)
            false
        }
    }

    fun sendnetinfo(node: String, data: ByteArray) = nameSendMessage(node, NET_PATH, data)

    fun sendbluetooth(name: String, on: Boolean) = sendbool(BLUETOOTH_PATH, name, on)

    /** Additive path (redesign): the watch asks the phone to take the sensor back. */
    fun sendtakeback(name: String) = nameSendMessage(name, TAKEBACK_PATH, byteArrayOf(1))

    private fun sendOnmessages(node: String, on: Boolean) {
        if (doLog) Log.i(LOG_ID, "sendNameMessageOn($node,$on)")
        sendbool(MESSAGES_PATH, node, on)
    }

    fun sendbool(path: String, nodeName: String, on: Boolean) {
        val onbyte: Byte = if (on) 1 else 0
        nameSendMessage(nodeName, path, byteArrayOf(onbyte))
    }

    fun findnodeid(id: String): Int {
        val nods = nodes ?: return -1
        nods.forEachIndexed { index, node -> if (node.id == id) return index }
        Log.e(LOG_ID, "Can't find $id")
        return -1
    }

    companion object {
        private const val LOG_ID = "MessageSender"
        const val WAKE_PATH = "/wake"
        const val WAKESTREAM_PATH = "/wakestream"
        const val NET_PATH = "/netinfo"
        const val START_PATH = "/start"
        const val ASKFORSTART_PATH = "/askforstart"
        const val DEFAULTS_PATH = "/defaults"
        const val SETTINGS_PATH = "/settings"
        const val BLUETOOTH_PATH = "/bluetooth"
        const val DATA_PATH = "/data"
        const val MESSAGES_PATH = "/messages"

        /** Additive (redesign only): watch → phone "take the sensor back" request. */
        const val TAKEBACK_PATH = "/takeback"

        /** Additive (redesign only): phone → watch display prefs (unit + target band). */
        const val UIPREFS_PATH = "/uiprefs"

        /** Additive (redesign only): either side → peer "an alarm was dismissed here". */
        const val ALARMDISMISS_PATH = "/alarmdismiss"

        /** Additive (redesign only): "peer reappeared — re-send readings I may have missed". */
        const val CATCHUP_PATH = "/catchup"

        /** Additive (redesign only): the recent-history batch answering a /catchup. */
        const val HISTORY_PATH = "/history"

        /** Additive (redesign only): phone → watch alarm timings (repeat;snooze;timeout). */
        const val WEARALARM_PATH = "/wearalarm"

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        private var messagesender: MessageSender? = null

        @JvmStatic
        fun getMessageSender(): MessageSender? = messagesender

        @JvmStatic
        fun sendaskforstart() {
            val sender = messagesender ?: return
            sender.sendmessage(ASKFORSTART_PATH, byteArrayOf(0))
        }

        /** Phone → watch: push the current display prefs (unit + target band, mg/dL). */
        @JvmStatic
        fun sendUiPrefs(unitMmol: Boolean, lowMgdl: Float, highMgdl: Float) {
            val sender = messagesender ?: return
            val payload = "${if (unitMmol) 1 else 0};$lowMgdl;$highMgdl".toByteArray()
            sender.sendmessage(UIPREFS_PATH, payload)
        }

        /** Either side → peer: an alarm was dismissed here; silence the peer's matching alarm. */
        @JvmStatic
        fun sendAlarmDismiss() {
            val sender = messagesender ?: return
            sender.sendmessage(ALARMDISMISS_PATH, byteArrayOf(1))
        }

        /** Ask peers to re-send readings we may have missed while disconnected. */
        @JvmStatic
        fun sendCatchup() {
            val sender = messagesender ?: return
            sender.sendmessage(CATCHUP_PATH, byteArrayOf(1))
        }

        /** Answer a /catchup with a recent-history batch, targeted at the requester. */
        @JvmStatic
        fun sendHistory(nodeId: String, payload: ByteArray) {
            val sender = messagesender ?: return
            sender.nameSendMessageResult(nodeId, HISTORY_PATH, payload)
        }

        /** Phone → watch: alarm timings (repeat / snooze minutes, timeout seconds). */
        @JvmStatic
        fun sendWearAlarm(repeatMin: Int, snoozeMin: Int, timeoutSec: Int) {
            val sender = messagesender ?: return
            sender.sendmessage(WEARALARM_PATH, "$repeatMin;$snoozeMin;$timeoutSec".toByteArray())
        }

        @JvmStatic
        fun sendwake() {
            val sender = messagesender ?: return
            sender.sendmessage(WAKE_PATH, byteArrayOf(0))
        }

        @JvmStatic
        fun sendwakestream() {
            val sender = messagesender ?: return
            sender.sendmessage(WAKESTREAM_PATH, byteArrayOf(0))
        }

        // --- JNI contract (watch/messages.cpp): exact names + signatures ---

        @Keep
        @JvmStatic
        fun sendDatawithName(ident: String, data: ByteArray): Boolean {
            val sender = messagesender ?: return false
            if (doLog) Log.i(LOG_ID, "sendDatawithName $ident")
            return sender.nameSendMessageResult(ident, DATA_PATH, data)
        }

        @Keep
        @JvmStatic
        fun sendData(data: ByteArray): Boolean {
            val sender = messagesender ?: run {
                Log.e(LOG_ID, "sendData messagesender==null")
                return false
            }
            val nodes = sender.nodes
            if (nodes == null) {
                Log.e(LOG_ID, "sendData nodes==null")
                scope.launch { sender.findWearDevicesWithApp() }
                return false
            }
            if (nodes.isEmpty()) {
                Log.e(LOG_ID, "sendData nodes.isEmpty()")
                return false
            }
            return sender.nameSendMessageResult(nodes.elementAt(0).id, DATA_PATH, data)
        }

        @Keep
        @JvmStatic
        fun sendNameMessageOn(ident: String, on: Boolean) {
            val sender = messagesender ?: run {
                Log.e(LOG_ID, "messagesender==null")
                return
            }
            sender.sendOnmessages(ident, on)
        }

        @Keep
        @JvmStatic
        fun sendMessageOn(on: Boolean) {
            val sender = messagesender ?: run {
                Log.e(LOG_ID, "sendMessageOn messagesender==null")
                return
            }
            val nodes = sender.nodes
            if (nodes == null) {
                Log.e(LOG_ID, "sendMessageOn nodes==null")
                scope.launch { sender.findWearDevicesWithApp() }
                return
            }
            if (nodes.isEmpty()) return
            sendNameMessageOn(nodes.elementAt(0).id, on)
        }

        // --- end JNI contract ---

        @JvmStatic
        fun initwearos(app: Context) {
            if (messagesender == null) {
                messagesender = MessageSender(app)
            }
        }

        @JvmStatic
        fun cansend(): Boolean {
            val sender: MessageSender = messagesender ?: return false
            val tmp = sender.nodes
            return !tmp.isNullOrEmpty()
        }

        private const val netwait = 1000L

        private fun inargsendnetinfo(id: String) {
            if (doLog) Log.i(LOG_ID, "sendnetinfo($id)")
            if (!cansend()) return
            val sender: MessageSender = messagesender ?: return
            val nodes = sender.nodes
            if (nodes.isNullOrEmpty()) {
                scope.launch { sender.findWearDevicesWithApp() }
                return
            }
            val times = sender.nexttimes
            if (times == null) {
                scope.launch { sender.findWearDevicesWithApp() }
                return
            }
            val it = sender.findnodeid(id)
            if (it < 0 || it >= times.size) {
                scope.launch { sender.findWearDevicesWithApp() }
                return
            }
            val othernode = nodes.elementAt(it)
            val nu = System.currentTimeMillis()
            if (times[it] > nu) return
            val netinfo: ByteArray? = if (isWearable) {
                Natives.getmynetinfo(sender.localnode, true, 0, true, 0)
            } else {
                Natives.getmynetinfo(id, false, 0, isGalaxy(othernode), 0)
            }
            if (netinfo == null) {
                Log.e(LOG_ID, "netinfo=null")
                return
            }
            sender.sendnetinfo(id, netinfo)
            times[it] = nu + netwait
        }

        @JvmStatic
        fun sendnetinfo(id: String) {
            scope.launch { inargsendnetinfo(id) }
        }

        private fun insendnetinfo() {
            val nu = System.currentTimeMillis()
            if (!cansend()) return
            val sender = messagesender ?: return
            val nodes = sender.nodes
            if (nodes.isNullOrEmpty()) {
                scope.launch { sender.findWearDevicesWithApp() }
                return
            }
            val times = sender.nexttimes
            // nodes and nexttimes are reassigned together in setnodes() but non-atomically and from
            // several IO threads, so this read can straddle the swap and capture them from different
            // generations. Bail (and rediscover) unless they're a consistent same-size pair —
            // indexing a shorter times[] would throw AIOOBE and kill the whole mirror send path.
            if (times == null || times.size != nodes.size) {
                scope.launch { sender.findWearDevicesWithApp() }
                return
            }
            val nextnetinfo = nu + netwait
            for (i in nodes.indices) {
                val node: Node = nodes.elementAt(i)
                if (times[i] < nu) {
                    val name = if (isWearable) sender.localnode else node.id
                    val netinfo = Natives.getmynetinfo(name, isWearable, 0, isGalaxy(node), 0) ?: continue
                    sender.sendnetinfo(node.id, netinfo)
                    times[i] = nextnetinfo
                }
            }
        }

        @JvmStatic
        fun sendnetinfo() {
            scope.launch { insendnetinfo() }
        }

        @JvmStatic
        fun isGalaxy(node: Node): Boolean {
            // Legacy ships with ALLGALAXY=true: every peer is treated as "galaxy", which
            // selects the robust Data-Layer message tunnel. Keep exact parity.
            if (Applic.ALLGALAXY) return true
            return node.displayName.startsWith("Galaxy Watch")
        }

        @JvmStatic
        fun reinit() {
            Log.i(LOG_ID, "reinit")
            Natives.resetnetwork()
            getMessageSender()?.nulltimes()
            sendnetinfo()
        }
    }
}
