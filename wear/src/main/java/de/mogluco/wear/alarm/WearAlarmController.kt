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
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import de.mogluco.data.SensorInfo
import de.mogluco.wear.WearApp
import tk.glucodata.WatchLink

/**
 * Watch port of the phone's AlarmController: decides *when* to ring (thresholds from the watch's
 * own target range, signal-loss watchdog) and drives [WearAlarmService]. Same dismiss/snooze/repeat
 * semantics as the phone: a dismiss silences the excursion until the value returns to range; a
 * snooze re-alerts after the snooze if still out of range (with the snooze off it silences the
 * excursion like a dismiss); a fresh excursion alarms at once, unless an alarm of the same kind
 * was dismissed within the repeat interval — a dismissed High or "Signal lost" never delays a Low.
 *
 * Repeat/snooze/duration come from the phone's Watch settings (defaults 15 min / 30 min / ring
 * until dismissed); the phone keeps the fully configurable alarm surface.
 */
object WearAlarmController {

    enum class Kind { LOW, HIGH, LOSS }

    private const val LOSS_MILLIS = 16 * 60_000L
    const val TIMEOUT_SECONDS = 60 // fallback / staleness constant

    private fun repeatMillis(context: Context) =
        WearApp.from(context).preferences.wearRepeatMinutes.value * 60_000L

    private var watchdogStarted = false
    private var loaded = false
    private var lastReadingMillis = 0L

    // When this process first looked — or the process before it, where this one goes on with its
    // window (see lossWindowStart). The loss deadline never counts from before it (see
    // lossDeadline), so a watch that was off or an app that was not running gives the link a
    // full window to deliver before "Signal lost" can ring.
    private var processStartMillis = 0L

    // The window start the previous process counted from, as it left it (0 = none known). A
    // process that the armed check itself had to start goes by it (see adoptArmedWindow).
    private var inheritedWindowStart = 0L

    // A live reading has arrived in this process: its windows are its own from then on.
    private var readingSinceStart = false

    /** Mirrors [lastReadingMillis] so the watchdog's deadline wait restarts on each reading. */
    private val lastReadingFlow = MutableStateFlow(0L)

    private var activeKind: Kind? = null
    private var dismissed = false
    private var snoozeUntil = 0L
    private var lastFiredMillis = 0L
    private var lastDismissMillis = 0L
    private var lastDismissKind: Kind? = null // what was dismissed then — only that kind is throttled

    private var persistedExcursion: String? = null // the excursion state as last written to disk

    // The excursion was restored as neither dismissed nor snoozed, so its alarm should be up
    // again as well; the first evaluation looks whether it is (see evaluate). Also set when the
    // system cut a ring off (see onRingCutOff): the next evaluation looks again.
    private var restoredRingExpected = false
    private var evaluated = false             // an evaluation has run in this process

    // The sensor in use when the signal was last found lost (null = not known then): what was
    // reported for it ends once the engine names another one as in use (see checkSignalLoss).
    private var lossSensorId: String? = null

    // The sensor in use as the engine named it when it was last asked (null = it could not be
    // asked, has none in use, or which one is the newest is not certain) — see
    // sensorReadingsDueSince.
    private var inUseSensorId: String? = null

    // When the AlarmManager signal-loss check is due (0 = not armed; -1 = not known yet: the
    // previous process may have left one armed, so the first decision always goes through).
    private var lossCheckArmedFor = -1L

    // When the check last ran with its deadline passed (0 = not in this process, or not since
    // the last reading). Until it has, the deadline having passed does not say so (see
    // firstLossCheckAt).
    private var lossCheckedAt = 0L

    /**
     * Pick up what a previous process left (idempotent): the last-reading marker, the excursion
     * state and the loss window. Every entry point calls it — a reading or a Dismiss can arrive
     * before [ensureWatchdog].
     */
    private fun load(app: WearApp) {
        if (loaded) return
        loaded = true
        val now = System.currentTimeMillis()
        seedLastReading(app.preferences.lastReadingEpochMillis.value)
        restoreExcursion(app)
        // An alarm whose notification outlived the previous process is still on display: its
        // state comes back with it, before anything here evaluates or answers a Dismiss.
        WearAlarmService.adoptPostedAlarm(app)
        // The window: this process's own, from now — or still the one on record, where the
        // process before it was already waiting out this silence (see lossWindowStart). That one
        // stays on record as it is; a fresh one replaces it.
        val saved = savedLossWindow(app)
        inheritedWindowStart = saved
        processStartMillis = lossWindowStart(
            saved, lastReadingMillis, now, bootMillis = now - SystemClock.elapsedRealtime(), LOSS_MILLIS,
        )
        if (processStartMillis != saved) saveLossWindow(app, processStartMillis)
    }

    /** The window start on record, as the process before this one left it (0 = none). */
    private fun savedLossWindow(app: WearApp): Long = runCatching {
        app.getSharedPreferences(EXCURSION_PREFS, Context.MODE_PRIVATE).getLong(KEY_LOSS_WINDOW, 0L)
    }.getOrDefault(0L)

    /** Put [start] on record as the window start in force. */
    private fun saveLossWindow(app: WearApp, start: Long) {
        runCatching {
            app.getSharedPreferences(EXCURSION_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LOSS_WINDOW, start).apply()
        }
    }

    /**
     * The check armed with AlarmManager has come due, and no reading has arrived in this process.
     * If the process is not the one that armed it — it was gone by then, and the alarm had to
     * start a new one — the fresh window of that process start must not count: the check would
     * find itself "not due" and re-arm a full window later, and again each time the process is
     * gone when it fires, so "Signal lost" would never ring from a process the check started. The
     * window it was armed for is the one already granted, so the check goes by that one, and it
     * stays the one on record for a process that this check has to start next.
     *
     * Where the process went on with the window on record from its start ([lossWindowStart]),
     * there is nothing left to adopt. That rule does not make this one redundant, though: it
     * leaves a window alone that began before the last reading — and the check armed for that
     * reading's deadline, the common case, starts a process that opens a fresh one — and one
     * from more than two windows ago, which a check that comes that late was still armed for.
     *
     * Any other process start that opens a fresh window (the app opened after a longer while,
     * boot, the first start after the last reading) keeps it: it moves the armed check to the
     * end of that window, so the earlier one is not delivered to it. In a process that armed the
     * check itself this changes nothing — its own, later deadline has passed as well.
     */
    private fun adoptArmedWindow(app: WearApp) {
        val inherited = inheritedWindowStart
        if (readingSinceStart || inherited <= 0L || inherited >= processStartMillis) return
        processStartMillis = inherited
        saveLossWindow(app, inherited)
    }

    /** Start the signal-loss watchdog (idempotent). Called from WearApp. */
    @Synchronized
    fun ensureWatchdog(context: Context) {
        if (watchdogStarted) return
        watchdogStarted = true
        val app = WearApp.from(context)
        load(app)
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
                // The delay below does not advance while the watch is suspended, and once
                // readings stop nothing else wakes it. So the same deadline is also armed with
                // AlarmManager — one alarm, moved here on every reading and pref change, and
                // cancelled when there is nothing to wait for (see armLossCheck).
                armLossCheck(app)
                if (!enabled || last == 0L) return@collectLatest
                while (true) {
                    val untilLoss = lossDeadline(last, processStartMillis, LOSS_MILLIS) -
                        System.currentTimeMillis()
                    if (untilLoss > 0) delay(untilLoss)
                    runLossCheck(app)
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
        load(app)
        val previousReading = lastReadingMillis
        if (epochMillis > lastReadingMillis) {
            lastReadingMillis = epochMillis
            lastReadingFlow.value = epochMillis
            readingSinceStart = true
            lossCheckedAt = 0L // the silence that check was about has ended
            prefs.setLastReadingEpochMillis(epochMillis)
        }
        val kind = when {
            prefs.lowAlarmEnabled.value && mgdl < prefs.targetLow.value -> Kind.LOW
            prefs.highAlarmEnabled.value && mgdl > prefs.targetHigh.value -> Kind.HIGH
            else -> null
        }
        evaluate(
            context, kind, "${prefs.unit.value.format(mgdl)} ${prefs.unit.value.label}",
            afterSilence = silenceEndsExcursion(activeKind, previousReading, epochMillis, LOSS_MILLIS),
        )
        persistExcursion(app)
    }

    /**
     * Dismiss/snooze/repeat state machine (mirrors the phone). [kind] null = in range → reset.
     * [afterSilence]: this reading is the first after a silence long enough to end a Low / High
     * (see [silenceEndsExcursion]) — one of the same kind is then a new excursion, not the old
     * one going on.
     */
    private fun evaluate(context: Context, kind: Kind?, detail: String, afterSilence: Boolean = false) {
        val now = System.currentTimeMillis()
        evaluated = true
        val ringExpected = restoredRingExpected
        restoredRingExpected = false
        // Whatever follows starts or ends an excursion, or rings: only an excursion that goes on
        // unchanged keeps the note that its last ring ran its whole duration.
        if (kind != activeKind || afterSilence) ringTimedOut = false
        if (kind == null) {
            // Also a ring the service restored after a process restart, or an alarm taken over
            // from its notification then (WearAlarmService.adoptPostedAlarm): the excursion
            // state is only restored while the last reading is recent (see restoreExcursion),
            // so activeKind alone may not say that an alarm is active.
            if (activeKind != null || WearAlarmService.ringing.value?.restored == true) {
                WearAlarmService.stop(context)
                activeKind = null
                dismissed = false
                snoozeUntil = 0L
            }
            return
        }
        val repeat = repeatMillis(context)
        if (activeKind != kind || afterSilence) {
            // A new excursion. Fire unless a recent dismiss of the same kind still holds it back.
            val throttled = dismissThrottles(kind, lastDismissKind, lastDismissMillis, now, repeat)
            activeKind = kind
            dismissed = false
            snoozeUntil = 0L
            if (!throttled) {
                fire(context, kind, detail)
                lastDismissMillis = 0L
            } else {
                // Held back: the repeat interval counts from that dismiss. Left at the earlier
                // ring, the repeat check below (and the re-arm in nextLossCheck) would already
                // be due with the next reading, and the throttle would hold for a minute
                // instead of the interval.
                lastFiredMillis = lastDismissMillis
                // Nothing rings for this excursion, so nothing replaces the alarm of the one it
                // follows (fire does, above): that one would ring on with its own text, and a
                // Dismiss on it would be taken for this excursion. (As on the phone. With the
                // excursion state intact no such ring is up here — any alarm that fired since the
                // dismiss cleared it — so this is for a ring whose state was lost on the way.)
                if (WearAlarmService.ringing.value?.let { ringOfEarlierExcursion(it.kind, it.test, kind) } == true) {
                    WearAlarmService.stop(context)
                }
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
        // An excursion restored after a process restart counts as "already fired" only if its
        // alarm came back too. The service restores a ring after a process death, but not after
        // a reboot, an app update or a force-stop, and a fallback alarm has no service to restore
        // it: the excursion would then stay silent until the repeat is due — for good with the
        // repeat off. So the first evaluation that still finds the condition rings, unless the
        // alarm is up. (If the service's restart lands after this instead, it finds the alarm
        // just started here: one ring either way.) Up means in progress in the service: an
        // alarm that is only still on display — since the system stopped the service under it
        // (see onRingCutOff), or taken over from a notification that outlived its process
        // (WearAlarmService.adoptPostedAlarm) — is as lost as one that never came back. That
        // one alerted when it was posted and is alerted once more here; restoredRingExpected
        // is spent with this evaluation, so not again until the repeat is due.
        val ringLost = ringExpected && !WearAlarmService.realRingSounding()
        if (ringLost || repeatDue(lastFiredMillis, now, repeat)) {
            fire(context, kind, detail)
        }
    }

    private fun fire(context: Context, kind: Kind, detail: String) {
        // 0 = ring until dismissed; a configured duration is floored at 10 s.
        val configured = WearApp.from(context).preferences.wearTimeoutSeconds.value.toInt()
        val timeout = if (configured <= 0) 0 else configured.coerceAtLeast(10)
        WearAlarmService.start(context, title(kind), detail, timeout, kind = kind)
        lastFiredMillis = System.currentTimeMillis()
        ringTimedOut = false
    }

    /** The watchdog's check, from its coroutine or from [WearSignalLossReceiver]; arms the next one. */
    @Synchronized
    private fun runLossCheck(app: WearApp) {
        checkSignalLoss(app)
        persistExcursion(app)
        armLossCheck(app)
    }

    /** [WearSignalLossReceiver]: the time armed with AlarmManager has come. */
    @Synchronized
    fun onLossAlarm(context: Context) {
        val app = WearApp.from(context)
        load(app)
        lossCheckArmedFor = 0L // a one-shot, now spent
        adoptArmedWindow(app)
        runLossCheck(app)
    }

    /**
     * Back the watchdog's wait with AlarmManager so it also ends while the watch is suspended:
     * armed for the loss deadline (for at once where that has passed and the check has not run
     * yet, see [firstLossCheckAt]), and once loss is pending for the time the alarm is next due to
     * re-alert (snooze end / repeat). A single one-shot that is moved, never a periodic alarm.
     * Cancelled when there is nothing to wait for — the option off, no sensor in use, or a
     * dismissed alarm that only a new reading can re-arm.
     */
    @Synchronized
    private fun armLossCheck(app: WearApp) {
        val at = nextLossCheck(app, System.currentTimeMillis())
        if (at == lossCheckArmedFor) return
        lossCheckArmedFor = at
        WearSignalLossReceiver.schedule(app, at)
    }

    /** When [checkSignalLoss] next has something to decide; 0 = not until a reading or pref change. */
    private fun nextLossCheck(app: WearApp, now: Long): Long {
        if (lastReadingMillis == 0L || !app.preferences.signalLossAlarm.value) return 0L
        val deadline = lossDeadline(lastReadingMillis, processStartMillis, LOSS_MILLIS)
        if (now < deadline) return deadline
        // Dismissed: silent until a reading — nothing to arm, and nothing for the check to
        // decide either. (That another sensor has come into use is for the check itself to find,
        // see checkSignalLoss; this only says when, and changes nothing.)
        if (activeKind == Kind.LOSS && dismissed) return 0L
        // A new sensor gets the full window from the end of its warm-up (see checkSignalLoss).
        val dueSince = sensorReadingsDueSince(app, now)
        if (dueSince == NO_SENSOR) return 0L
        // Still ahead for that sensor — or all of it has passed without the check having run
        // since: a Dismiss or Snooze that arms from here must not take that for "checked" and
        // cancel it. It has run once it ran at or after this moment (see checkSignalLoss), so
        // also for the second of the two windows, which a check from before its end says
        // nothing about.
        val due = maxOf(deadline, dueSince + LOSS_MILLIS)
        firstLossCheckAt(due, checked = lossCheckedAt >= due, armedFor = lossCheckArmedFor, now)
            .let { if (it != 0L) return it }
        // The check has run. Not ringing: nothing to repeat.
        if (activeKind != Kind.LOSS) return 0L
        val repeat = repeatMillis(app)
        val next = when {
            snoozeUntil > 0L -> snoozeUntil
            repeat > 0L -> lastFiredMillis + repeat
            else -> return 0L
        }
        return if (next > now) next else now + 60_000L
    }

    /**
     * [readingsDueSince] for the sensors the engine has in use. On the watch that is the one
     * place to ask: in mirror mode the repository's sensor only follows the readings, so it still
     * shows the old sensor after it was deactivated on the phone and all through the warm-up of
     * its successor. When the engine can't be asked, only that sensor's warm-up holds the alarm
     * back — in doubt, it rings.
     *
     * Also notes which sensor that is ([inUseSensorId]), for [checkSignalLoss]: only on the
     * engine's word, never the repository's sensor, which can still be the previous one.
     */
    private fun sensorReadingsDueSince(app: WearApp, now: Long): Long {
        val inUse = runCatching {
            if (!tk.glucodata.Engine.isStarted()) return@runCatching null // sample data: no engine
            tk.glucodata.Sensors.activeSensors()
                .filterNot { tk.glucodata.Sensors.sensorState(it) == SENSOR_ENDED }
                .map { serial ->
                    val start = tk.glucodata.Sensors.sensorStartMillis(serial)
                    val info = SensorInfo(id = serial, activationMillis = start)
                    // Its own wear time (14 or 15 days), as the sensor card has it: by the
                    // default 15 a 14-day sensor would count as in use for a day after its end.
                    val officialEnd = runCatching {
                        tk.glucodata.Sensors.sensorOfficialEndMillis(serial)
                    }.getOrDefault(0L)
                    sensorLifetimeDays(start, officialEnd)?.let { info.copy(lifetimeDays = it) } ?: info
                }
        }.getOrNull()
        if (inUse == null) {
            inUseSensorId = null
            val sensor = app.glucoseRepository.currentSensor
            return if (sensor != null && sensor.activationMillis > 0L) sensor.warmupEndMillis() else 0L
        }
        inUseSensorId = sensorInUseId(inUse, now)
        return readingsDueSince(inUse, now)
    }

    /**
     * What "Signal lost" has reported — the alarm and its excursion — was about a sensor that is
     * no longer the one in use: another one took its place. Its signal is not there to be lost
     * any more, and nothing of it may hold back the alarm of the sensor that follows: not a
     * Dismiss (that excursion would stay silent until a reading, which a sensor that never
     * starts does not deliver), not an excursion that already rang (with the repeat off it would
     * not ring again), and not the throttle a Dismiss leaves behind. (As on the phone; the watch
     * has no quiet signal-loss notification to take down with it.)
     *
     * [stopRing] false = the caller rings for the new sensor right away, which replaces a ring
     * that is still up (see fire) where it is. Stopped first, that ring would give up the
     * foreground for nothing, and the new one would have to ask for it again (the instance
     * itself stays for a start that is already on its way, see WearAlarmService.stopRinging).
     */
    private fun endLossReport(app: WearApp, stopRing: Boolean) {
        lossSensorId = null
        if (activeKind == Kind.LOSS) {
            // Only where there is something to end. Mostly the alarm has long been answered or
            // timed out, and a stop then only makes the system create the service to stop it
            // again.
            if (stopRing && WearAlarmService.ringUpOrPending(app)) WearAlarmService.stop(app)
            activeKind = null
            dismissed = false
            snoozeUntil = 0L
            ringTimedOut = false
            // Ended by this process: a Dismiss / Snooze that crosses with it must not bring the
            // excursion back from the ring (see adoptRestoredRing).
            evaluated = true
        }
        if (lastDismissKind == Kind.LOSS) lastDismissMillis = 0L
        persistExcursion(app)
    }

    @Synchronized
    private fun checkSignalLoss(app: WearApp) {
        if (!app.preferences.signalLossAlarm.value) return
        if (lastReadingMillis == 0L) return
        val now = System.currentTimeMillis()
        if (now < lossDeadline(lastReadingMillis, processStartMillis, LOSS_MILLIS)) return
        // From here the check has run for this silence, whatever it decides (see nextLossCheck):
        // noted with its time, which also says whether a new sensor's window had passed by then.
        lossCheckedAt = now
        // Dismissed: silent until a reading — or until another sensor is in use than the one it
        // was dismissed for, which is asked below. Where that one is not known, nothing to ask.
        if (activeKind == Kind.LOSS && dismissed && lossSensorId == null) return
        // Only a sensor that is in use and past its warm-up has a signal that can be lost: not
        // one that expired, ended or was deactivated, nor a new one that is silent for its first
        // hour (the stale last-reading time belongs to the PREVIOUS sensor then). The end of the
        // warm-up counts like a reading: the first one gets the same window from there as any
        // other silence — it is only just due then, and not yet missing.
        val dueSince = sensorReadingsDueSince(app, now)
        val lost = dueSince != NO_SENSOR && now >= dueSince + LOSS_MILLIS
        // A "Signal lost" still standing from the sensor before the one in use ends here, the
        // one place where the watch learns of the change: this check, each time it runs once
        // the reading-based deadline has passed. From here on the new sensor's own window counts.
        if (lossOfAnotherSensor(lossSensorId, inUseSensorId)) endLossReport(app, stopRing = !lost)
        if (activeKind == Kind.LOSS && dismissed) return // the same sensor: silent until a reading
        if (!lost) return
        // What is reported from here on is about this sensor. Where the engine named none, the
        // one known from before stands.
        inUseSensorId?.let { lossSensorId = it }
        evaluate(app.applicationContext, Kind.LOSS, "No recent readings")
    }

    /** Manual test from watch settings: rings with no glucose gating. */
    fun test(context: Context) {
        // Flagged as a test: stopping it must not dismiss a real excursion or silence the phone.
        WearAlarmService.start(
            context, "Test alarm", "This is how alarms feel on the watch.", 15, test = true,
        )
    }

    /**
     * A Dismiss / Snooze answers the alarm it was tapped on ([ringKind], null = not known). Where
     * the excursion has become another one in the moment the tap was on its way (a reading that
     * ends "Signal lost" with a Low, say), it is not that one's answer: the new alarm, queued
     * right behind, would ring once and then stay silent as "dismissed", repeat or not.
     */
    private fun answersAnotherExcursion(ringKind: Kind?): Boolean =
        ringKind != null && activeKind != null && ringKind != activeKind

    @Synchronized
    fun onUserDismiss(context: Context, ringKind: Kind? = null) {
        val app = WearApp.from(context)
        load(app)
        if (answersAnotherExcursion(ringKind)) return
        markDismissed(app)
        // Mirror the dismissal to the phone so its matching alarm silences too.
        runCatching { WatchLink.pushAlarmDismiss() }
    }

    private fun markDismissed(app: WearApp) {
        load(app)
        adoptRestoredRing(app)
        // No excursion to mark — a dismiss from the phone for an alarm this device does not have:
        // nothing is noted. Noted all the same, it would put "no kind" in place of the dismiss
        // that still holds back the next excursion, and that one would ring inside the interval.
        if (activeKind == null) return
        dismissed = true
        lastDismissMillis = System.currentTimeMillis()
        lastDismissKind = activeKind
        snoozeUntil = 0L
        persistExcursion(app)
        armLossCheck(app)
    }

    /** The phone dismissed its alarm — silence ours and adopt the dismissed state (no loop). */
    @Synchronized
    fun onRemoteDismiss(context: Context) {
        markDismissed(WearApp.from(context))
        WearAlarmService.stop(context)
    }

    @Synchronized
    fun onUserSnooze(context: Context, ringKind: Kind? = null) {
        val app = WearApp.from(context)
        load(app)
        if (answersAnotherExcursion(ringKind)) return
        adoptRestoredRing(app)
        // Snooze "Off" in the phone's Watch settings means Snooze won't re-alert: silent for this
        // excursion — snoozing until "now" would instead ring again with the very next reading.
        val mins = app.preferences.wearSnoozeMinutes.value.coerceAtLeast(0L)
        if (mins == 0L) {
            dismissed = true
            snoozeUntil = 0L
        } else {
            snoozeUntil = System.currentTimeMillis() + mins * 60_000L
            dismissed = false
        }
        persistExcursion(app)
        armLossCheck(app)
    }

    /**
     * A Dismiss / Snooze can land on a ring from before a process restart whose excursion was not
     * restored (the last reading was too old, see [restoreExcursion]). With no excursion to mark,
     * the tap would not stick: the next out-of-range reading would count as a new excursion and
     * ring again a minute later. So the excursion is taken from that ring — unless all that is
     * left of it is a notification from longer ago than a restart is owed (see
     * WearAlarmService.realRingKind): then the tap ends that alarm, and the next reading out of
     * range rings as the new excursion it may well be.
     *
     * Only until the first evaluation: from then on the excursion state is this process's own,
     * and a ring it has just ended (back in range) must not be turned into a dismissed excursion
     * by a tap that crosses with it.
     */
    private fun adoptRestoredRing(app: WearApp) {
        if (activeKind != null || evaluated) return
        activeKind = WearAlarmService.realRingKind(app)
    }

    /**
     * [WearAlarmService] was taken down while a real alarm ([kind], null = not known) was still
     * ringing. Dismiss, Snooze, the timeout and the condition resolving all end the ring first, so
     * this was the system: it stops a service it refused the foreground (a ring restored after a
     * process death, on a watch that is not exempt from battery optimisation) after about a
     * minute in the background. The alarm stays on display, silent, and the excursion would count
     * as "fired" all the same and stay quiet until the repeat is due — for good with the repeat
     * off. So the next evaluation that still finds the condition rings again, as after a restart
     * that did not bring the ring back ([restoredRingExpected]).
     */
    @Synchronized
    fun onRingCutOff(context: Context, kind: Kind?) {
        val app = WearApp.from(context)
        load(app)
        // Only the excursion that ring belonged to, and only one still waiting for an answer.
        if (activeKind == null || (kind != null && kind != activeKind)) return
        if (dismissed || snoozeUntil > 0L) return
        restoredRingExpected = true
        if (activeKind == Kind.LOSS) {
            // A Low / High is looked at again with the next reading. Signal loss has none
            // coming, and with the repeat off nothing is armed either (see nextLossCheck): look
            // again in a minute, the watchdog's cadence while loss is pending — the one-shot
            // check moved there, so also while the watch is suspended.
            val at = System.currentTimeMillis() + 60_000L
            lossCheckArmedFor = at
            WearSignalLossReceiver.schedule(app, at)
        }
    }

    // The excursion's last ring ended with its own duration: it has rung, and with the repeat off
    // it is not owed again — not after a process restart either (see ringExpectedOnRestore).
    private var ringTimedOut = false

    /**
     * A real alarm ([kind], null = not known) went quiet after its configured duration. Not a
     * dismiss or snooze: the excursion stays armed for the repeat. Only noted, for a restart:
     * without it the saved state reads like that of a ring cut short by the process ending, and
     * the excursion would ring again after every restart — with the repeat off, too.
     */
    @Synchronized
    fun onTimeout(context: Context, kind: Kind?) {
        val app = WearApp.from(context)
        load(app)
        if (activeKind == null || (kind != null && kind != activeKind)) return
        ringTimedOut = true
        // Nor is it a ring that failed to come back after a restart: where the service restored
        // it and it has now run its time, the first reading must not ring it once more as lost.
        restoredRingExpected = false
        persistExcursion(app)
    }

    /**
     * Save the excursion state (only when it changed), so that a Dismiss or Snooze survives a
     * process restart — see [restoreExcursion].
     */
    private fun persistExcursion(app: WearApp) {
        val state = listOf(
            activeKind?.name.orEmpty(), dismissed, snoozeUntil, lastFiredMillis,
            lastDismissMillis, lastDismissKind?.name.orEmpty(), lossSensorId.orEmpty(), ringTimedOut,
        ).joinToString("|")
        if (state == persistedExcursion) return
        persistedExcursion = state
        runCatching {
            app.getSharedPreferences(EXCURSION_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_EXCURSION, state).apply()
        }
    }

    /**
     * Pick the excursion up where the previous process left it. After process death the service
     * brings the ring back, but with the state gone a Dismiss or Snooze on it did not stick: the
     * next out-of-range reading counted as a new excursion and rang again.
     *
     * Low/High state is only trusted while the last reading is recent (the phone's rule) — after
     * a longer blackout the value may have been back in range unseen, and then ringing again is
     * the safe side. Signal loss needs no such limit: it ends with a reading, and those only
     * arrive in-process — or with its sensor, which is why the sensor it was about is kept with
     * it ([lossSensorId]; absent in a state saved by an earlier version, then not known).
     *
     * What does not come back by itself is the alarm of an excursion that was neither dismissed
     * nor snoozed — [evaluate] rings it again if the service has not ([restoredRingExpected]).
     * Not one that never rang because a dismiss was still holding it back: that stays quiet until
     * its repeat is due, as it would have without the restart. Nor one whose alarm had rung for
     * its whole duration ([ringTimedOut]; absent in a state saved by an earlier version, then
     * taken as not).
     */
    private fun restoreExcursion(app: WearApp) {
        val state = runCatching {
            app.getSharedPreferences(EXCURSION_PREFS, Context.MODE_PRIVATE).getString(KEY_EXCURSION, null)
        }.getOrNull() ?: return
        val parts = state.split('|')
        if (parts.size !in 6..8) return
        val kind = kindOf(parts[0])
        val recent = System.currentTimeMillis() - lastReadingMillis <= RESTORE_MAX_GAP_MS
        if (kind != Kind.LOSS && !recent) return
        activeKind = kind
        lossSensorId = parts.getOrNull(6)?.takeIf { it.isNotEmpty() }
        dismissed = parts[1].toBoolean()
        snoozeUntil = parts[2].toLongOrNull() ?: 0L
        lastFiredMillis = parts[3].toLongOrNull() ?: 0L
        lastDismissMillis = parts[4].toLongOrNull() ?: 0L
        lastDismissKind = kindOf(parts[5])
        ringTimedOut = kind != null && parts.getOrNull(7).toBoolean()
        restoredRingExpected = kind != null && ringExpectedOnRestore(
            dismissed, snoozeUntil,
            heldBack = dismissThrottles(
                kind, lastDismissKind, lastDismissMillis, System.currentTimeMillis(), repeatMillis(app),
            ),
            timedOut = ringTimedOut,
        )
        persistedExcursion = state
    }

    internal fun kindOf(name: String?): Kind? = Kind.entries.firstOrNull { it.name == name }

    private const val EXCURSION_PREFS = "wear_alarm_excursion"
    private const val KEY_EXCURSION = "state"

    /**
     * Since when the loss window in force counts ([processStartMillis]): what the next process
     * goes on with or replaces (see [lossWindowStart]), and what [adoptArmedWindow] goes by.
     */
    private const val KEY_LOSS_WINDOW = "loss_window_start"

    /** How old the last reading may be for a restart to still continue a Low/High excursion. */
    private const val RESTORE_MAX_GAP_MS = 10 * 60_000L

    /** Engine sensor state: the sensor itself reported the end of its life. */
    private const val SENSOR_ENDED = 9

    private fun title(kind: Kind) = when (kind) {
        Kind.LOW -> "Low glucose"
        Kind.HIGH -> "High glucose"
        Kind.LOSS -> "Signal lost"
    }
}

/**
 * Whether a new excursion of [kind] is still held back by a recent dismiss: only by one of the
 * same kind, and only within the repeat window. A dismissed High or "Signal lost" must never
 * delay a Low (correct a high with insulin and the low that follows is the alarm that matters
 * most).
 */
internal fun dismissThrottles(
    kind: WearAlarmController.Kind,
    dismissedKind: WearAlarmController.Kind?,
    dismissMillis: Long,
    now: Long,
    repeatMillis: Long,
): Boolean =
    repeatMillis > 0 && dismissMillis > 0L && dismissedKind == kind && (now - dismissMillis) < repeatMillis

/**
 * Whether a Low / High excursion ([activeKind]) is over because the readings stopped under it:
 * the reading timed [readingMillis] comes at least [lossMillis] after the one before it
 * ([previousReadingMillis], 0 = none before) — the silence "Signal lost" is raised for. Until an
 * in-range reading arrives a Low stays one, dismissed or rung out; but through a silence that
 * long the value may have been back in range unseen, and a Low at its end is then a new one that
 * has to ring. With the signal-loss alarm on, that alarm already takes the excursion's place
 * when the silence has lasted this long; this makes the outcome the same with it off, or where
 * its check comes late. Whether the new excursion rings is still up to [dismissThrottles]. (As
 * on the phone.)
 */
internal fun silenceEndsExcursion(
    activeKind: WearAlarmController.Kind?,
    previousReadingMillis: Long,
    readingMillis: Long,
    lossMillis: Long,
): Boolean =
    (activeKind == WearAlarmController.Kind.LOW || activeKind == WearAlarmController.Kind.HIGH) &&
        previousReadingMillis > 0L && readingMillis - previousReadingMillis >= lossMillis

/**
 * Whether a ring that is still up ([ringKind], null = not known; [ringIsTest]) belongs to an
 * earlier excursion than the one of [kind] that has just begun, and so has to end with it. Not a
 * test ring, and not one of unknown kind: when in doubt it keeps ringing.
 */
internal fun ringOfEarlierExcursion(
    ringKind: WearAlarmController.Kind?,
    ringIsTest: Boolean,
    kind: WearAlarmController.Kind,
): Boolean = !ringIsTest && ringKind != null && ringKind != kind

/**
 * Whether a signal loss reported while [lossSensorId] was the sensor in use is another sensor's
 * than the one in use now ([sensorId]): it was replaced, with or without "Deactivate". Only where
 * both are known — with no sensor named (null) then or now nothing says so, and what was reported
 * stands.
 */
internal fun lossOfAnotherSensor(lossSensorId: String?, sensorId: String?): Boolean =
    lossSensorId != null && sensorId != null && lossSensorId != sensorId

/** Whether the auto-repeat is due: [repeatMillis] after [sinceMillis]; never with the repeat off (0). */
internal fun repeatDue(sinceMillis: Long, now: Long, repeatMillis: Long): Boolean =
    repeatMillis > 0 && now - sinceMillis >= repeatMillis

/**
 * Whether an excursion restored after a process restart had an alarm ringing (or due to ring
 * again by itself) that the user had not answered: not if it was dismissed or snoozed, and not if
 * it never rang because a dismiss was still holding it back ([heldBack], see [dismissThrottles]).
 * Nor if its last ring had ended with its own duration ([timedOut]): that alarm was not cut
 * short, and whether it rings again is the repeat's business ([repeatDue]), as without a restart.
 */
internal fun ringExpectedOnRestore(
    dismissed: Boolean,
    snoozeUntil: Long,
    heldBack: Boolean,
    timedOut: Boolean = false,
): Boolean = !dismissed && snoozeUntil == 0L && !heldBack && !timedOut

/**
 * When "Signal lost" is due: [lossMillis] after the last reading, but never sooner than that
 * after the process started. At a process start the last reading can be hours old (watch off,
 * app updated) without the link having had any chance to deliver yet. ([processStartMillis] is
 * the start of an earlier process where this one goes on with its window, see [lossWindowStart].)
 */
internal fun lossDeadline(lastReadingMillis: Long, processStartMillis: Long, lossMillis: Long): Long =
    maxOf(lastReadingMillis, processStartMillis) + lossMillis

/**
 * Since when the loss window of a process that starts at [now] counts (see [lossDeadline]): from
 * its own start — or from the one on record ([savedStart], 0 = none), where the process before it
 * was already waiting out the same silence. A watch that is not exempt from battery optimisation
 * loses the process in the background and gets it back from a complication poll, a tile request
 * or a message of the link, more often than once a window. With a window of its own each time,
 * every start would move the armed check a full window on, and where the process is gone again
 * before that, "Signal lost" would not ring for as long as this goes on.
 *
 * The window on record goes on only where it still says what a fresh one is there to say — that
 * the link has had this long to deliver:
 *  - It began after the last reading ([lastReadingMillis]). One from before it belongs to a
 *    silence that has ended, and counts for nothing next to that reading's own deadline: the
 *    first start after a reading opens the window of the silence that follows, however old the
 *    reading is by then.
 *  - The watch has been up ever since ([bootMillis], when it was started, lies before it). After
 *    a reboot the link has to be set up again, and gets a full window for that.
 *  - It does not lie in the future (the clock was set back): nothing can be told from it then.
 *  - It is no older than two windows. The first is the window itself; the second is the time in
 *    which what comes next finds it passed — the armed check, minutes late where it is inexact,
 *    or the process start after it, where those come at least once a window. A record from
 *    longer ago vouches for nothing: the app may have been stopped all the while, and one that
 *    was long gone must not ring the moment it is opened.
 */
internal fun lossWindowStart(
    savedStart: Long,
    lastReadingMillis: Long,
    now: Long,
    bootMillis: Long,
    lossMillis: Long,
): Long = when {
    savedStart <= 0L || savedStart <= lastReadingMillis -> now
    savedStart <= bootMillis || savedStart > now -> now
    now - savedStart > 2 * lossMillis -> now
    else -> savedStart
}

/**
 * When the first check of a silence is due: at its [deadline] — and now, once that has passed
 * without the check having run since ([checked] false). A deadline that has passed does not say
 * the check ran: the watchdog's `delay` stands still while the watch sleeps, and the AlarmManager
 * one-shot may come minutes late where it is inexact. A Dismiss or Snooze that arms in between
 * would cancel it, and "Signal lost" would not ring until the watch had been awake for the rest
 * of the delay. (As on the phone.)
 *
 * Not a new "now" each time, though: where a check was armed after the deadline and is due as
 * well ([armedFor], a "now" from before), that one is on its way, and stays — armed again for
 * every Dismiss or Snooze, it would only be pushed back. The one armed for the deadline itself
 * is replaced: it is the late one, and the watch is awake now.
 *
 * 0 = it has run: from there only a re-alert is left to wait for — so "now" is handed out until
 * the check has run, and not once more after it.
 */
internal fun firstLossCheckAt(deadline: Long, checked: Boolean, armedFor: Long, now: Long): Long = when {
    now < deadline -> deadline
    checked -> 0L
    armedFor in (deadline + 1)..now -> armedFor
    else -> now
}

/**
 * A sensor's nominal wear time in whole days, from its activation and the engine's official end
 * of it — the arithmetic of the sensor card (NativeGlucoseRepository.lifetimeDays, which is
 * private to the repository). Null where the engine knows no end or no activation (0): the
 * [SensorInfo] default stays.
 */
internal fun sensorLifetimeDays(startMillis: Long, officialEndMillis: Long): Int? {
    if (startMillis <= 0L || officialEndMillis <= 0L) return null
    return ((officialEndMillis - startMillis + DAY_MS / 2) / DAY_MS).toInt().takeIf { it > 0 }
}

private const val DAY_MS = 24 * 60 * 60_000L

/** [readingsDueSince]: there is no sensor whose readings could go missing. */
internal const val NO_SENSOR = Long.MAX_VALUE

/**
 * Since when readings are due from the sensors in use ([sensors], ended ones left out): the end
 * of the newest one's warm-up, until which it is legitimately silent — still ahead of [now] while
 * it warms up. From then on their absence is a lost signal, also for a sensor that is in use and
 * has merely gone silent. 0 = its activation is not known (taken as in use and warmed up).
 * [NO_SENSOR] = none is within its life.
 */
internal fun readingsDueSince(sensors: List<SensorInfo>, now: Long): Long {
    val newest = sensors.filter { it.activationMillis <= 0L || !it.isExpired(now) }
        .maxByOrNull { it.activationMillis } ?: return NO_SENSOR
    return if (newest.activationMillis > 0L) newest.warmupEndMillis() else 0L
}

/**
 * Which of the sensors in use ([sensors], as for [readingsDueSince]) is the one in use now: the
 * newest within its life. Null = none — or not certain: with more than one and a start that is
 * not known (the engine can report 0 for a moment), "the newest" would be a guess, and a guess
 * must not end what was reported for another sensor (see [lossOfAnotherSensor]).
 */
internal fun sensorInUseId(sensors: List<SensorInfo>, now: Long): String? {
    val inLife = sensors.filter { it.activationMillis <= 0L || !it.isExpired(now) }
    if (inLife.size > 1 && inLife.any { it.activationMillis <= 0L }) return null
    return inLife.maxByOrNull { it.activationMillis }?.id
}
