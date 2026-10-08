/*      Modified 2026 by Joshua Meyer as part of Mogluco, a Material 3               */
/*      redesign fork of Juggluco. See the NOTICE file for details.                  */
/*                                                                                   */
/*      Phone ↔ watch Data-Layer receive side. Ported from the legacy MessageReceiver   */
/*      (GPLv3, Copyright (C) 2021 Jaap Korthals Altes) with legacy-UI calls replaced    */
/*      by Engine/WatchLink hooks. Declared in :core's manifest so it merges into both   */
/*      the phone and watch apps — the mirror is symmetric.                              */

package tk.glucodata

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.launch
import tk.glucodata.Applic.isWearable
import tk.glucodata.Log.doLog
import tk.glucodata.MessageSender.Companion.isGalaxy
import tk.glucodata.MessageSender.Companion.sendnetinfo
import tk.glucodata.Natives.setWearosdefaults

class MessageReceiver : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        // GMS can wake this service before the app's own boot path ran — the engine must
        // be up before any Natives call below.
        Engine.start(applicationContext)
        val data = messageEvent.data
        val path = messageEvent.path
        Log.i(LOG_ID, "onMessageReceived start $path")
        when (path) {
            MessageSender.DEFAULTS_PATH -> {
                val sender = MessageSender.getMessageSender() ?: return
                val source = sender.localnode
                if (doLog) Log.i(LOG_ID, "DEFAULTS_PATH $source")
                setWearosdefaults(source, true)
                Applic.setbluetooth(false)
            }
            MessageSender.WAKE_PATH -> Natives.wakehereonly()
            MessageSender.WAKESTREAM_PATH -> Natives.wakestreamhereonly()
            MessageSender.DATA_PATH -> Natives.message(data)
            MessageSender.NET_PATH -> {
                val sender = MessageSender.getMessageSender() ?: return
                val nodes = sender.nodes
                if (nodes.isNullOrEmpty()) {
                    MessageSender.scope.launch { sender.findWearDevicesWithApp() }
                    return
                }
                val sourceId = messageEvent.sourceNodeId
                val name: String
                val galaxy: Boolean
                if (isWearable) {
                    name = sender.localnode
                    galaxy = true
                } else {
                    name = sourceId
                    val it = sender.findnodeid(sourceId)
                    if (it < 0) return
                    val node: Node = nodes.elementAt(it)
                    galaxy = isGalaxy(node)
                }
                if (Natives.setmynetinfo(name, data, galaxy)) {
                    sendnetinfo(sourceId)
                }
            }
            MessageSender.START_PATH -> {
                Applic.initStarted = Natives.ontbytesettings(data)
                Log.i(LOG_ID, "START_PATH initStarted=" + Applic.initStarted)
                if (isWearable) {
                    // Only hold the forced Wi-Fi network when the mirror is configured to use it
                    // (same gate as WatchLink.init). Requested unconditionally, nothing ever
                    // released it: the watch kept Wi-Fi up/scanning around the clock even though
                    // the Data-Layer tunnel carries all mirror traffic (ALLGALAXY) — a large,
                    // system-attributed battery drain. Read after ontbytesettings so the phone's
                    // just-delivered setting decides.
                    if (runCatching { Natives.getWifi() }.getOrDefault(false)) {
                        UseWifi.usewifi()
                    } else {
                        UseWifi.stopusewifi()
                    }
                    if (Applic.initStarted) WatchLink.markBootstrapped()
                }
                // Settings + roles just changed underneath the repositories — refresh.
                Engine.dispatchInvalidate()
                sendnetinfo(messageEvent.sourceNodeId)
            }
            MessageSender.SETTINGS_PATH -> {
                Natives.ontbytesettings(data)
                Engine.dispatchInvalidate()
            }
            MessageSender.MESSAGES_PATH -> {
                val sender = MessageSender.getMessageSender() ?: return
                val sourceId = messageEvent.sourceNodeId
                val name: String = (if (isWearable) sender.localnode else sourceId) ?: return
                Natives.setBlueMessage(name, booldata(data))
            }
            MessageSender.BLUETOOTH_PATH -> {
                val on = booldata(data)
                if (doLog) Log.i(LOG_ID, "set bluetooth $on")
                Applic.setbluetooth(on)
            }
            MessageSender.ASKFORSTART_PATH -> {
                if (!isWearable) {
                    val sender = MessageSender.getMessageSender() ?: return
                    val sourceId = messageEvent.sourceNodeId
                    val it = sender.findnodeid(sourceId)
                    if (it < 0) {
                        MessageSender.scope.launch { sender.findWearDevicesWithApp() }
                        return
                    }
                    WatchLink.sendInitWatchApp(sourceId)
                }
            }
            MessageSender.TAKEBACK_PATH -> {
                // Additive (redesign): the watch asked the phone to reclaim the sensor.
                if (!isWearable) {
                    WatchLink.takeBackSensor(messageEvent.sourceNodeId)
                }
            }
            MessageSender.UIPREFS_PATH -> {
                // Additive (redesign): phone → watch display prefs (unit + target band, mg/dL).
                if (isWearable) {
                    val parts = String(data).split(";")
                    if (parts.size == 3) {
                        val low = parts[1].toFloatOrNull()
                        val high = parts[2].toFloatOrNull()
                        if (low != null && high != null) {
                            WatchLink.onUiPrefsReceived(parts[0] == "1", low, high)
                        }
                    }
                }
            }
            MessageSender.ALARMDISMISS_PATH -> {
                // Additive (redesign): the peer dismissed an alarm — silence ours too.
                WatchLink.onAlarmDismissReceived()
            }
            MessageSender.CATCHUP_PATH -> {
                // Additive (redesign): a peer reappeared — answer with our recent stored history so
                // it can fill any gap from while it was disconnected.
                val payload = WatchLink.buildCatchupPayload()
                if (payload.isNotEmpty()) {
                    MessageSender.sendHistory(messageEvent.sourceNodeId, payload)
                }
            }
            MessageSender.HISTORY_PATH -> {
                // Additive (redesign): a catch-up batch — merge it into our store (dedupes).
                WatchLink.onHistoryReceived(data)
            }
            MessageSender.WEARALARM_PATH -> {
                // Additive (redesign): phone → watch alarm timings.
                if (isWearable) {
                    val p = String(data).split(";")
                    if (p.size == 3) {
                        val r = p[0].toIntOrNull(); val s = p[1].toIntOrNull(); val t = p[2].toIntOrNull()
                        if (r != null && s != null && t != null) WatchLink.onWearAlarmReceived(r, s, t)
                    }
                }
            }
        }
        Log.i(LOG_ID, "onMessageReceived end $path")
    }

    companion object {
        private const val LOG_ID = "MessageReceiver"
        private const val offbyte: Byte = 0
        fun booldata(data: ByteArray): Boolean = data[0] != offbyte
    }
}
