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
package de.mogluco.wear.alarm

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import de.mogluco.wear.WearApp
import tk.glucodata.WatchLink

/**
 * Watch port of the phone's AlarmController: decides *when* to ring (thresholds from the watch's
 * own target range, signal-loss watchdog) and drives [WearAlarmService]. Same dismiss/snooze/repeat
 * semantics as the phone: a dismiss silences the excursion until the value returns to range; a
 * snooze re-alerts after the snooze if still out of range; a fresh excursion always alarms.
 *
 * Repeat/snooze/timeout use fixed sensible values (15 min / 30 min / 60 s); the phone keeps the
 * fully configurable alarm surface.
 */
object WearAlarmController {

    enum class Kind { LOW, HIGH, LOSS }

    private const val LOSS_MILLIS = 16 * 60_000L
    const val TIMEOUT_SECONDS = 60 // fallback / staleness constant

    private fun repeatMillis(context: Context) =
        WearApp.from(context).preferences.wearRepeatMinutes.value * 60_000L

    private var watchdogStarted = false
    private var lastReadingMillis = 0L

    /** Mirrors [lastReadingMillis] so the watchdog's deadline wait restarts on each reading. */
    private val lastReadingFlow = MutableStateFlow(0L)

    private var activeKind: Kind? = null
    private var dismissed = false
    private var snoozeUntil = 0L
    private var lastFiredMillis = 0L
    private var lastDismissMillis = 0L

    /** Start the signal-loss watchdog (idempotent). Called from WearApp. */
    @Synchronized
    fun ensureWatchdog(context: Context) {
        if (watchdogStarted) return
        watchdogStarted = true
        val app = WearApp.from(context)
        lastReadingMillis = app.preferences.lastReadingEpochMillis.value
        lastReadingFlow.value = lastReadingMillis
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                app.glucoseRepository.history.firstOrNull()?.lastOrNull()?.epochMillis
            }.getOrNull()?.let { seedLastReading(it) }
            // Loss is the *absence* of readings, so it can't be detected on arrival — but a
            // minute poll (~1440 CPU wakeups/day) is wasteful when the deadline is known. Sleep
            // until the newest reading's loss deadline instead; a fresh reading or pref change
            // restarts the wait (collectLatest). Only while loss is actually pending does the
            // loop fall back to a minute cadence, preserving the old repeat/snooze re-fire timing.
            combine(lastReadingFlow, app.preferences.signalLossAlarm) { last, enabled ->
                last to enabled
            }.collectLatest { (last, enabled) ->
                if (!enabled || last == 0L) return@collectLatest
                while (true) {
                    val untilLoss = last + LOSS_MILLIS - System.currentTimeMillis()
                    if (untilLoss > 0) delay(untilLoss)
                    checkSignalLoss(app)
                    delay(60_000)
                }
            }
        }
    }

    @Synchronized
    private fun seedLastReading(millis: Long) {
        if (millis > lastReadingMillis) {
            lastReadingMillis = millis
            lastReadingFlow.value = millis
        }
    }

    /** Evaluate a live reading (mg/dL). [epochMillis] is the reading's own timestamp. */
    @Synchronized
    fun onReading(context: Context, mgdl: Double, epochMillis: Long) {
        val app = WearApp.from(context)
        val prefs = app.preferences
        if (epochMillis > lastReadingMillis) {
            lastReadingMillis = epochMillis
            lastReadingFlow.value = epochMillis
            prefs.setLastReadingEpochMillis(epochMillis)
        }
        val kind = when {
            prefs.lowAlarmEnabled.value && mgdl < prefs.targetLow.value -> Kind.LOW
            prefs.highAlarmEnabled.value && mgdl > prefs.targetHigh.value -> Kind.HIGH
            else -> null
        }
        evaluate(context, kind, "${prefs.unit.value.format(mgdl)} ${prefs.unit.value.label}")
    }

    /** Dismiss/snooze/repeat state machine (mirrors the phone). [kind] null = in range → reset. */
    private fun evaluate(context: Context, kind: Kind?, detail: String) {
        val now = System.currentTimeMillis()
        if (kind == null) {
            if (activeKind != null) {
                WearAlarmService.stop(context)
                activeKind = null
                dismissed = false
                snoozeUntil = 0L
            }
            return
        }
        val repeat = repeatMillis(context)
        if (activeKind != kind) {
            val throttled = repeat > 0 && lastDismissMillis > 0L && (now - lastDismissMillis) < repeat
            activeKind = kind
            dismissed = false
            snoozeUntil = 0L
            if (!throttled) {
                fire(context, kind, detail)
                lastDismissMillis = 0L
            }
            return
        }
        if (dismissed) return
        if (snoozeUntil > 0L) {
            if (now >= snoozeUntil) {
                snoozeUntil = 0L
                fire(context, kind, detail)
            }
            return
        }
        if (repeat > 0 && now - lastFiredMillis >= repeat) {
            fire(context, kind, detail)
        }
    }

    private fun fire(context: Context, kind: Kind, detail: String) {
        val timeout = WearApp.from(context).preferences.wearTimeoutSeconds.value.toInt().coerceAtLeast(10)
        WearAlarmService.start(context, title(kind), detail, timeout)
        lastFiredMillis = System.currentTimeMillis()
    }

    @Synchronized
    private fun checkSignalLoss(app: WearApp) {
        if (!app.preferences.signalLossAlarm.value) return
        if (lastReadingMillis == 0L) return
        if (app.glucoseRepository.currentSensor?.isWarmingUp() == true) return
        if (System.currentTimeMillis() - lastReadingMillis < LOSS_MILLIS) return
        evaluate(app.applicationContext, Kind.LOSS, "No recent readings")
    }

    /** Manual test from watch settings: rings with no glucose gating. */
    fun test(context: Context) {
        WearAlarmService.start(context, "Test alarm", "This is how alarms feel on the watch.", 15)
    }

    @Synchronized
    fun onUserDismiss() {
        dismissed = true
        lastDismissMillis = System.currentTimeMillis()
        snoozeUntil = 0L
        // Mirror the dismissal to the phone so its matching alarm silences too.
        runCatching { WatchLink.pushAlarmDismiss() }
    }

    /** The phone dismissed its alarm — silence ours and adopt the dismissed state (no loop). */
    @Synchronized
    fun onRemoteDismiss(context: Context) {
        dismissed = true
        lastDismissMillis = System.currentTimeMillis()
        snoozeUntil = 0L
        WearAlarmService.stop(context)
    }

    @Synchronized
    fun onUserSnooze(context: Context) {
        snoozeUntil = System.currentTimeMillis() +
            WearApp.from(context).preferences.wearSnoozeMinutes.value * 60_000L
        dismissed = false
    }

    private fun title(kind: Kind) = when (kind) {
        Kind.LOW -> "Low glucose"
        Kind.HIGH -> "High glucose"
        Kind.LOSS -> "Signal lost"
    }
}
