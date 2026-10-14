package de.mogluco.notification

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import de.mogluco.MoglucoApp
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.SensorInfo

/**
 * Decides *when* to raise a glucose alarm and drives [AlarmService] (which does the ringing).
 *
 * Evaluated on every reading: a value below/above the user's target range raises a Low/High alarm; a
 * gap with no readings raises a signal-loss alarm. The re-alert behaviour follows the user's rules:
 *  - Repeat interval 0 ⇒ no automatic repeat; only a *new* excursion (back in range, then out
 *    again) alarms again.
 *  - Dismiss ⇒ the current excursion stays silent no matter how long it lasts; it re-arms only when
 *    the value returns to range, and the next excursion of the same kind is still throttled by the
 *    repeat interval (a dismissed High never delays a Low).
 *  - Snooze ⇒ re-alerts once the reminder interval passes, but only if still out of range; a fresh
 *    excursion during the snooze alarms immediately. With the reminder off, Snooze silences the
 *    excursion like Dismiss.
 *
 * Thresholds in [de.mogluco.data.AppPreferences] are stored in mg/dL, matching the reading's mg/dL.
 */
object AlarmController {

    enum class Kind { LOW, HIGH, LOSS }

    private lateinit var app: MoglucoApp
    private var started = false

    private var lastReadingMillis = 0L
    private var lastPersistedMillis = 0L

    /** Mirrors [lastReadingMillis] so the watchdog's deadline wait restarts on each reading. */
    private val lastReadingFlow = MutableStateFlow(0L)

    // Per-excursion state driving the dismiss/snooze/repeat semantics.
    private var activeKind: Kind? = null      // the ongoing out-of-range kind, or null when in range
    private var dismissed = false             // dismissed → silent for this excursion until back in range
    private var snoozeUntil = 0L              // snoozed → re-fire at this time if still out of range
    private var lastFiredMillis = 0L          // when the alarm last rang (repeat throttle)
    private var lastDismissMillis = 0L        // when the user last dismissed (throttles the next excursion)
    private var lastDismissKind: Kind? = null // what was dismissed then — only that kind is throttled
    private var signalLossNotified = false    // a signal-loss NOTIFICATION is currently posted
    // The process before this one may have left its signal-loss notification up (it was killed,
    // not stopped): only the process that posted one knows of it. So until this process has
    // taken one down, the first occasion to do so takes down whatever may be there.
    private var signalLossMayBeLeft = true
    // The excursion's last ring ended with its own duration: it has rung, and with the repeat off
    // it is not owed again — not after a process restart either (see ringExpectedOnRestore).
    private var ringTimedOut = false

    private var persistedExcursion: String? = null // the excursion state as last written to disk

    // The excursion was restored as one whose alarm had neither been dismissed nor snoozed, so
    // the ring should be up again as well; evaluate looks whether it is (see there).
    private var restoredRingExpected = false
    private var evaluated = false             // an evaluation has run in this process
    private var startedElapsed = 0L           // SystemClock.elapsedRealtime() at init
    private var lossCheckArmedFor = 0L        // when the AlarmManager signal-loss check is due (0 = not armed)

    // The silence (its start, see lossSince) that the check last ran for with its deadline
    // passed. Until it has, the deadline having passed does not say so (see firstLossCheckAt).
    private var lossCheckedSince = 0L

    // The sensor in use as the watchdog last saw it, and since when this process has seen it in
    // use (see lossSince).
    private var seenSensorId: String? = null
    private var seenSensorMillis = 0L

    // The last reading as it stood when another sensor came into use: readings up to there were
    // the previous sensor's, and do not say that this one has delivered (see lossCountsFrom).
    private var readingOfPreviousSensor = 0L

    // The sensor in use when the signal was last found lost (null = none shown then, or not
    // known): what was reported for it ends once another sensor is in use (see lossSince).
    private var lossSensorId: String? = null

    // Synchronized: a reading must not be evaluated between `started` and the restored state.
    @Synchronized
    fun init(application: MoglucoApp) {
        if (started) return
        started = true
        app = application
        startedElapsed = SystemClock.elapsedRealtime()
        // The watchdog compares against the last reading time, which is process-local: after a
        // process restart it would read 0 and the "never had a sensor" guard would disarm signal
        // loss entirely until the app is opened. Seed from the persisted marker (and below from
        // the repository's history tail, which can be newer than the throttled write).
        lastReadingMillis = app.preferences.lastReadingEpochMillis.value
        lastReadingFlow.value = lastReadingMillis
        // The sensor in use at the process start counts as seen from here, whichever of the
        // watchdog's entry points comes first.
        lossSince(lastReadingMillis, System.currentTimeMillis())
        restoreExcursion()
        CoroutineScope(Dispatchers.Default).launch {
            val tail = runCatching {
                app.glucoseRepository.history.firstOrNull()?.lastOrNull()?.epochMillis
            }.getOrNull() ?: 0L
            seedLastReading(tail)
        }
        // Deactivating the sensor is the one way the sensor card goes back to null. From then on
        // there is no signal left to lose, so drop the marker instead of crying "Signal lost"
        // sixteen minutes later (and again after every restart). The watchdog is armed again by
        // the next sensor's card, counting from the end of its warm-up (see lossCountsFrom).
        CoroutineScope(Dispatchers.Default).launch {
            var hadSensor = false
            app.glucoseRepository.sensor.collect { sensor ->
                if (sensor == null && hadSensor) onSensorDeactivated()
                hadSensor = sensor != null
            }
        }
        // Signal-loss watchdog: loss is the *absence* of readings, so it can't be detected on
        // arrival — but a minute poll (~1440 CPU wakeups/day) is wasteful when the deadline is
        // known. Sleep until the newest reading's loss deadline instead; a fresh reading, a pref
        // change or another sensor restarts the wait (collectLatest). Only while loss is actually
        // pending does the loop fall back to a minute cadence, preserving the old repeat/snooze
        // re-fire timing.
        CoroutineScope(Dispatchers.Default).launch {
            val prefs = app.preferences
            // The sensor in use moves the deadline as well (see lossCountsFrom) — the sensor
            // itself, not its error banner coming and going.
            val sensorInUse = app.glucoseRepository.sensor
                .map { it?.let { s -> s.activationMillis to s.expiryMillis() } }
                .distinctUntilChanged()
            combine(
                lastReadingFlow,
                prefs.signalLossAlarm,
                prefs.signalLossNotification,
                prefs.signalLossMinutes,
                sensorInUse,
            ) { last, alarm, notify, minutes, _ -> Triple(last, alarm || notify, minutes) }
                .collectLatest { (last, enabled, minutes) ->
                    // The delay below does not advance while the CPU is suspended, and once
                    // readings stop nothing else wakes an idle phone. So the same deadline is
                    // also armed with AlarmManager — re-armed here on every reading, pref and
                    // sensor change, cancelled when there is nothing to wait for (see
                    // armLossCheck).
                    armLossCheck()
                    if (!enabled) return@collectLatest
                    while (true) {
                        val now = System.currentTimeMillis()
                        val since = lossSince(last, now)
                        if (since == 0L) return@collectLatest // no reading yet and no sensor
                        val untilLoss = since + minutes.coerceAtLeast(1) * 60_000L - now
                        if (untilLoss > 0) delay(untilLoss)
                        runLossCheck()
                        delay(60_000)
                    }
                }
        }
    }

    /** Advance the last-reading marker to [millis] if it's newer; never rewinds it. */
    @Synchronized
    private fun seedLastReading(millis: Long) {
        if (millis > lastReadingMillis) {
            lastReadingMillis = millis
            lastReadingFlow.value = millis
        }
    }

    /** Evaluate a freshly arrived reading (mg/dL). Safe to call from any thread. */
    @Synchronized
    fun onReading(context: Context, mgdl: Double) {
        if (!started) return
        // A reading means the signal is back — clear any posted signal-loss notification.
        if (signalLossNotified || signalLossMayBeLeft) {
            signalLossNotified = false
            signalLossMayBeLeft = false
            WatchNotifications.cancelSignalLoss(context)
        }
        val previousReading = lastReadingMillis
        lastReadingMillis = System.currentTimeMillis()
        lastReadingFlow.value = lastReadingMillis
        // Persist so the watchdog survives a process restart — throttled to one write a minute.
        if (lastReadingMillis - lastPersistedMillis >= 60_000L) {
            lastPersistedMillis = lastReadingMillis
            app.preferences.setLastReadingEpochMillis(lastReadingMillis)
        }
        val prefs = app.preferences
        val low = prefs.targetLow.value.toDouble()
        val high = prefs.targetHigh.value.toDouble()
        val kind = when {
            prefs.lowAlarmEnabled.value && mgdl < low -> Kind.LOW
            prefs.highAlarmEnabled.value && mgdl > high -> Kind.HIGH
            else -> null
        }
        val lossMs = prefs.signalLossMinutes.value.coerceAtLeast(1) * 60_000L
        evaluate(
            context, kind, valueText(mgdl, prefs.unit.value),
            afterSilence = silenceEndsExcursion(activeKind, previousReading, lastReadingMillis, lossMs),
        )
        persistExcursion()
    }

    /**
     * The dismiss/snooze/repeat state machine. [kind] null means in range — that resets the
     * excursion (and silences a ringing alarm, including signal loss, since a reading means the
     * signal is back).
     *
     * [afterSilence]: this reading is the first after a silence long enough to end a Low / High
     * (see [silenceEndsExcursion]) — one of the same kind is then a new excursion, not the old
     * one going on.
     */
    private fun evaluate(context: Context, kind: Kind?, detail: String, afterSilence: Boolean = false) {
        val now = System.currentTimeMillis()
        evaluated = true
        val ringExpected = restoredRingExpected
        restoredRingExpected = false
        if (kind == null) {
            // Also a ring the service restored after a process restart: the excursion state is
            // only restored while the last reading is recent (see restoreExcursion), so
            // activeKind alone may not say that an alarm is active.
            if (activeKind != null || AlarmService.ringing.value?.restored == true) {
                AlarmService.stop(context)
                activeKind = null
                dismissed = false
                snoozeUntil = 0L
                ringTimedOut = false
            }
            return
        }
        val repeat = repeatMillis()
        if (activeKind != kind || afterSilence) {
            // A new excursion. Fire unless a recent dismiss of the same kind is still throttling it
            // within the repeat window (L2: re-alert only once the repeat time has passed AND we
            // left range again).
            val throttled = dismissThrottles(kind, lastDismissKind, lastDismissMillis, now, repeat)
            activeKind = kind
            dismissed = false
            snoozeUntil = 0L
            ringTimedOut = false
            if (!throttled) {
                fire(context, kind, detail)
                lastDismissMillis = 0L
            } else {
                // Held back: the repeat interval counts from that dismiss. Left at the earlier
                // ring, the repeat check below would already be due with the next reading, and
                // the throttle would hold for a minute instead of the interval.
                lastFiredMillis = lastDismissMillis
                // Nothing rings for this excursion, so nothing replaces the alarm of the one it
                // follows (fire does, above): "Signal lost" would ring on while values arrive
                // again, and a Dismiss on it would be taken for this excursion.
                if (AlarmService.ringing.value?.let { ringOfEarlierExcursion(it.kind, it.test, kind) } == true) {
                    AlarmService.stop(context)
                }
            }
            return
        }
        // Ongoing same excursion.
        if (dismissed) return // stays dismissed until the value returns to range (L2)
        if (snoozeUntil > 0L) {
            if (now >= snoozeUntil) {
                snoozeUntil = 0L
                fire(context, kind, detail) // re-alert after the snooze, still out of range (L3)
            }
            return
        }
        // An excursion restored after a process restart counts as "already fired" only if its
        // alarm came back too. The service restores a ring after a process death, but not after
        // a reboot, an app update or a force-stop, and a fallback alarm has no service to restore
        // it: the excursion would then stay silent until the repeat is due — for good with the
        // repeat off. So the first evaluation that still finds the condition rings, unless the
        // alarm is up. Right after the process start the service's restart may not have landed
        // yet (the signal-loss check runs at once): while it can still bring the ring back, this
        // waits for the next evaluation rather than ringing a second time on top of it.
        var ringLost = false
        if (ringExpected && AlarmService.ringing.value.let { it == null || it.test }) {
            if (ringRestartWait() > 0L) restoredRingExpected = true else ringLost = true
        }
        // Not dismissed, not snoozed: auto-repeat unless the interval is 0 (L1: no repeat).
        if (ringLost || repeatDue(lastFiredMillis, now, repeat)) {
            fire(context, kind, detail)
        }
    }

    private fun fire(context: Context, kind: Kind, detail: String) {
        AlarmService.start(context, title(kind), detail, app.preferences.alarmTimeoutSeconds.value, kind = kind)
        lastFiredMillis = System.currentTimeMillis()
        ringTimedOut = false
    }

    /** How long a restart of [AlarmService] may still bring back the ring it saved; 0 = it won't. */
    private fun ringRestartWait(): Long = ringRestartWaitMillis(
        AlarmService.savedRealRingPending(app), SystemClock.elapsedRealtime() - startedElapsed,
    )

    /** The watchdog's check, from its coroutine or from [SignalLossReceiver]; arms the next one. */
    @Synchronized
    private fun runLossCheck() {
        if (!started) return
        checkSignalLoss()
        persistExcursion()
        armLossCheck()
    }

    /** [SignalLossReceiver]: the time armed with AlarmManager has come. */
    @Synchronized
    fun onLossAlarm() {
        lossCheckArmedFor = 0L // a one-shot, now spent
        runLossCheck()
    }

    /**
     * Back the watchdog's wait with AlarmManager so it also ends in Doze: armed for the loss
     * deadline, and once loss is pending for the time the alarm is next due to re-alert (snooze
     * end / repeat). Cancelled when there is nothing to wait for — both options off, neither a
     * reading nor a sensor yet, a dismissed alarm that only a new reading can re-arm, or an alarm
     * for a sensor that has since expired or ended (see [lossRealertAt]).
     */
    @Synchronized
    private fun armLossCheck() {
        if (!started) return
        val at = nextLossCheck(System.currentTimeMillis())
        if (at == lossCheckArmedFor) return
        lossCheckArmedFor = at
        SignalLossReceiver.schedule(app, at)
    }

    /** When [checkSignalLoss] next has something to decide; 0 = not until a reading or pref change. */
    private fun nextLossCheck(now: Long): Long {
        val prefs = app.preferences
        val alarm = prefs.signalLossAlarm.value
        // A new sensor's deadline already lies past its warm-up (see lossCountsFrom).
        val since = lossSince(lastReadingMillis, now)
        if (since == 0L || !(alarm || prefs.signalLossNotification.value)) return 0L
        val deadline = since + prefs.signalLossMinutes.value.coerceAtLeast(1) * 60_000L
        // Still ahead — or passed without the check having run for this silence: a Dismiss or
        // Snooze that arms from here must not take that for "checked" and cancel it.
        firstLossCheckAt(deadline, checked = lossCheckedSince == since, now).let { if (it != 0L) return it }
        // The check has run. Not ringing as an alarm (notification only, or no sensor), or
        // dismissed: nothing to repeat.
        if (!alarm || activeKind != Kind.LOSS || dismissed) return 0L
        return lossRealertAt(
            // The check decides nothing for a sensor that has expired or ended (see
            // checkSignalLoss), so there is nothing to look at again either.
            sensorActive = hasActiveSensor(),
            // Still waiting to see whether the restored ring comes back (see evaluate): look
            // again when that is settled.
            ringRestartWaitMillis = if (restoredRingExpected) ringRestartWait() else 0L,
            snoozeUntil = snoozeUntil,
            lastFiredMillis = lastFiredMillis,
            repeatMillis = repeatMillis(),
            now = now,
        )
    }

    /**
     * [lossCountsFrom] for the sensor in use now. Whoever asks first after another sensor came
     * into use stamps it as seen: a sensor taken over or scanned again while it is long past its
     * warm-up gets its window from there, not an alarm the moment it is registered. And a
     * "Signal lost" still standing from the sensor before it ends there (see
     * [lossOfAnotherSensor]) — every decision of the watchdog asks here first.
     */
    @Synchronized
    private fun lossSince(lastReading: Long, now: Long): Long {
        val sensor = app.glucoseRepository.currentSensor
        if (sensor?.id != seenSensorId) {
            // One sensor directly after another (the first one of the process is the one the
            // marker is from, as far as anyone can tell): what was read so far was the other's.
            if (seenSensorId != null && sensor != null) readingOfPreviousSensor = lastReading
            seenSensorId = sensor?.id
            seenSensorMillis = now
        }
        if (lossOfAnotherSensor(lossSensorId, sensor?.id)) endLossReport()
        return lossCountsFrom(lastReading, sensor, seenSensorMillis, now, readingOfPreviousSensor)
    }

    /**
     * Whether there is a sensor whose readings could go missing. Not after it was deactivated, has
     * ended or has expired — but a sensor that is in use and has merely stopped sending counts,
     * that being the case the signal-loss alarm exists for.
     */
    private fun hasActiveSensor(): Boolean {
        if (!tk.glucodata.Engine.isStarted()) return true // sample data: no engine to ask
        val sensor = app.glucoseRepository.currentSensor
        if (sensor != null) {
            return !sensor.isExpired() && !sensorEnded(sensor.id)
        }
        // No sensor card: deactivated — or this process has not heard from its sensor yet (it
        // only appears with the first connection or reading), so ask the engine what is active.
        // When in doubt, keep the alarm.
        return runCatching {
            tk.glucodata.Sensors.activeSensors().any { !sensorEnded(it) }
        }.getOrDefault(true)
    }

    /** The sensor itself reported the end of its life (engine state 9). */
    private fun sensorEnded(serial: String): Boolean =
        runCatching { tk.glucodata.Sensors.sensorState(serial) == 9 }.getOrDefault(false)

    @Synchronized
    private fun onSensorDeactivated() {
        lastReadingMillis = 0L
        lastPersistedMillis = 0L
        lastReadingFlow.value = 0L // also cancels the armed check (watchdog collector)
        app.preferences.setLastReadingEpochMillis(0L)
        // A Low / High was that sensor's as well. Its ring ends here, and neither a Dismiss nor a
        // snooze nor a ring that ran out may hold back the first reading of the sensor that
        // follows — with the marker gone that reading does not come "after a silence" either
        // (see silenceEndsExcursion), so the excursion would simply go on. What a recent dismiss
        // holds back stays (dismissThrottles): the same sensor scanned again a moment later does
        // not ring anew. Also where this process holds no excursion for it: a ring the service
        // restored after a restart rings without one when the last reading was too old to
        // restore the state (see restoreExcursion) — and a Dismiss on it afterwards would turn
        // it into a dismissed excursion (adoptRestoredRing) for the next sensor to inherit.
        if (activeKind != Kind.LOSS) {
            if (AlarmService.ringing.value?.test != true && AlarmService.ringUpOrPending(app)) AlarmService.stop(app)
            activeKind = null
            dismissed = false
            snoozeUntil = 0L
            ringTimedOut = false
        }
        // Ended by this process: a Dismiss / Snooze that crosses with it must not bring the
        // excursion back from the ring (see adoptRestoredRing).
        evaluated = true
        endLossReport() // also saves the state
    }

    /**
     * What "Signal lost" has reported — the notification, the alarm and its excursion — was about
     * a sensor that is no longer the one in use: it was deactivated, or another one took its
     * place without that. Its signal is not there to be lost any more, and nothing of it may
     * hold back the alarm of the sensor that follows: not a Dismiss (that excursion would stay
     * silent until a reading, which a sensor that never starts does not deliver), not an
     * excursion that already rang (with the repeat off it would not ring again), and not the
     * throttle a Dismiss leaves behind.
     */
    private fun endLossReport() {
        lossSensorId = null
        lossCheckedSince = 0L // that check was about the other sensor
        if (signalLossNotified || signalLossMayBeLeft) {
            signalLossNotified = false
            signalLossMayBeLeft = false
            WatchNotifications.cancelSignalLoss(app)
        }
        if (activeKind == Kind.LOSS) {
            // Only where there is a ring to end. Mostly it has long timed out, and a stop then
            // only makes the system create the service to stop it again — right before the
            // alarm of the sensor that follows is raised (the same pass, see lossSince).
            if (AlarmService.ringUpOrPending(app)) AlarmService.stop(app)
            activeKind = null
            dismissed = false
            snoozeUntil = 0L
            ringTimedOut = false
            // Ended by this process: a Dismiss / Snooze that crosses with it must not bring the
            // excursion back from the ring (see adoptRestoredRing).
            evaluated = true
        }
        if (lastDismissKind == Kind.LOSS) lastDismissMillis = 0L
        persistExcursion()
    }

    @Synchronized
    private fun checkSignalLoss() {
        if (!started) return
        val prefs = app.preferences
        val now = System.currentTimeMillis()
        // A freshly activated sensor is silent for its 60-min warm-up; a last-reading time from
        // before belongs to the PREVIOUS sensor, so don't cry "Signal lost" while the new one
        // warms up — the window counts from the end of the warm-up then (see lossCountsFrom).
        val since = lossSince(lastReadingMillis, now)
        if (since == 0L) return // neither a reading nor a sensor yet; nothing to lose
        val lossMs = prefs.signalLossMinutes.value.coerceAtLeast(1) * 60_000L
        if (now - since < lossMs) return
        // From here the check has run for this silence, whatever it decides (see nextLossCheck).
        lossCheckedSince = since
        // Nothing whose signal could be lost. A "Signal lost" from before the sensor expired or
        // ended is left as it is — a ring still up is the user's to answer, and a reading still
        // ends it — it only is not looked at again (see nextLossCheck).
        if (!hasActiveSensor()) return
        // What is reported from here on is about this sensor. With none shown (not heard from
        // yet after a process start) the one known from before the restart stands.
        seenSensorId?.let { lossSensorId = it }
        // The alarm (rings) and the notification (quiet) are independent toggles sharing the delay.
        if (prefs.signalLossAlarm.value) {
            evaluate(app.applicationContext, Kind.LOSS, "No recent readings")
        }
        if (prefs.signalLossNotification.value && !signalLossNotified) {
            signalLossNotified = true
            WatchNotifications.signalLoss(app.applicationContext, prefs.signalLossMinutes.value)
        }
    }

    /** A manual "Test alarm" from settings: ring until dismissed/timeout, no glucose gating. */
    fun test(context: Context) {
        // Flagged as a test: stopping it must not dismiss a real excursion or silence the watch.
        AlarmService.start(
            context, "Test alarm", "This is how your alarm will sound and vibrate.", testTimeout(), test = true,
        )
    }

    /**
     * A Dismiss / Snooze answers the ring it was tapped on ([ringKind], null = not known). Where
     * the excursion has become another one in the moment the tap was on its way (a reading that
     * ends "Signal lost" with a Low, say), it is not that one's answer: the new alarm, queued
     * right behind, would ring once and then stay silent as "dismissed", repeat or not.
     */
    private fun answersAnotherExcursion(ringKind: Kind?): Boolean =
        ringKind != null && activeKind != null && ringKind != activeKind

    @Synchronized
    fun onUserDismiss(context: Context, ringKind: Kind? = null) {
        if (answersAnotherExcursion(ringKind)) return
        // Stay silent for this excursion until the value returns to range; the next excursion is
        // throttled by the repeat interval (L2). The alarm UI already stopped the ring.
        markDismissed()
        // Mirror the dismissal to the watch so its matching alarm silences too.
        runCatching { tk.glucodata.WatchLink.pushAlarmDismiss() }
    }

    private fun markDismissed() {
        adoptRestoredRing()
        // No excursion to mark — a dismiss from the watch for an alarm this device does not have
        // (its own "Signal lost", or a Low it is still ringing after the value came back here):
        // nothing is noted. Noted all the same, it would put "no kind" in place of the dismiss
        // that still holds back the next excursion, and that one would ring inside the interval.
        if (activeKind == null) return
        dismissed = true
        lastDismissMillis = System.currentTimeMillis()
        lastDismissKind = activeKind
        snoozeUntil = 0L
        persistExcursion()
        armLossCheck()
    }

    /** The watch dismissed its alarm — silence ours and adopt the same dismissed state (no loop). */
    @Synchronized
    fun onRemoteDismiss(context: Context) {
        if (!started) return
        markDismissed()
        AlarmService.stop(context)
    }

    @Synchronized
    fun onUserSnooze(context: Context, ringKind: Kind? = null) {
        if (answersAnotherExcursion(ringKind)) return
        // Re-alert after the reminder interval, only if still out of range (L3). Reminder "Off"
        // means Snooze won't re-alert: silent for this excursion, as the settings row says —
        // snoozing until "now" would instead ring again with the very next reading.
        adoptRestoredRing()
        val mins = app.preferences.reminderRepeatMinutes.value.coerceAtLeast(0)
        if (mins == 0) {
            dismissed = true
            snoozeUntil = 0L
        } else {
            snoozeUntil = System.currentTimeMillis() + mins * 60_000L
            dismissed = false
        }
        persistExcursion()
        armLossCheck()
    }

    /**
     * A Dismiss / Snooze can land on a ring from before a process restart whose excursion was not
     * restored (the last reading was too old, see [restoreExcursion]). With no excursion to mark,
     * the tap would not stick: the next out-of-range reading would count as a new excursion and
     * ring again a minute later. So the excursion is taken from that ring.
     *
     * Only until the first evaluation: from then on the excursion state is this process's own,
     * and a ring it has just ended (back in range) must not be turned into a dismissed excursion
     * by a tap that crosses with it.
     */
    private fun adoptRestoredRing() {
        if (activeKind != null || evaluated) return
        activeKind = AlarmService.realRingKind(app)
    }

    /** A real alarm ([kind], null = not known) auto-silenced after its duration. */
    @Synchronized
    fun onTimeout(context: Context, kind: Kind?) {
        // This is NOT a dismiss or snooze: the excursion stays armed so the repeat interval (if
        // any) re-alerts a persistent condition. Only noted, for a restart: without it the saved
        // state reads like that of a ring cut short by the process ending, and the excursion
        // would ring again after every restart — with the repeat off, too.
        if (!started || activeKind == null || (kind != null && kind != activeKind)) return
        ringTimedOut = true
        // Nor is it a ring that failed to come back after a restart: where the service restored
        // it and it has now run its time, the first reading must not ring it once more as lost.
        restoredRingExpected = false
        persistExcursion()
    }

    /**
     * [AlarmService] was taken down while a real alarm ([kind], null = not known) was still
     * ringing. Dismiss, Snooze, the timeout and the condition resolving all end the ring first, so
     * this was the system: it stops a service it refused the foreground after about a minute in
     * the background. The excursion would count as "fired" all the same and stay silent until
     * the repeat is due — for good with the repeat off. So the next evaluation that still finds
     * the condition rings again, as after a restart that did not bring the ring back
     * ([restoredRingExpected]).
     */
    @Synchronized
    fun onRingCutOff(kind: Kind?) {
        if (!started) return
        // Only the excursion that ring belonged to, and only one still waiting for an answer.
        if (activeKind == null || (kind != null && kind != activeKind)) return
        if (dismissed || snoozeUntil > 0L) return
        restoredRingExpected = true
        if (activeKind == Kind.LOSS) {
            // A Low / High is looked at again with the next reading. Signal loss has none
            // coming, and with the repeat off nothing is armed either (see nextLossCheck): look
            // again in a minute, the watchdog's cadence while loss is pending — also in Doze.
            val at = System.currentTimeMillis() + 60_000L
            lossCheckArmedFor = at
            SignalLossReceiver.schedule(app, at)
        }
    }

    /**
     * Save the excursion state (only when it changed), so that a Dismiss or Snooze survives a
     * process restart — see [restoreExcursion].
     */
    private fun persistExcursion() {
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
     * Low/High state is only trusted while the last reading is recent — after a longer blackout
     * the value may have been back in range unseen, and then ringing again is the safe side.
     * Signal loss needs no such limit: it ends with a reading, and those only arrive in-process —
     * or with its sensor, which is why the sensor it was about is kept with it ([lossSensorId];
     * absent in a state saved by an earlier version, then not known).
     *
     * What does not come back by itself is the alarm of an excursion that was neither dismissed
     * nor snoozed — [evaluate] rings it again if the service has not ([restoredRingExpected]).
     * Unless that alarm had rung for its whole duration ([ringTimedOut]; absent in a state saved
     * by an earlier version, then taken as not).
     */
    private fun restoreExcursion() {
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
            heldBack = dismissThrottles(kind, lastDismissKind, lastDismissMillis, System.currentTimeMillis(), repeatMillis()),
            timedOut = ringTimedOut,
        )
        persistedExcursion = state
    }

    internal fun kindOf(name: String?): Kind? = Kind.entries.firstOrNull { it.name == name }

    private const val EXCURSION_PREFS = "alarm_excursion"
    private const val KEY_EXCURSION = "state"

    /** How old the last reading may be for a restart to still continue a Low/High excursion. */
    private const val RESTORE_MAX_GAP_MS = 10 * 60_000L

    /**
     * How long after the process start a restart of [AlarmService] can still be on its way. The
     * system starts a service it owes a restart as soon as the process is up, so this only has to
     * cover the start-up itself.
     */
    internal const val RING_RESTART_GRACE_MS = 10_000L

    private fun repeatMillis(): Long = app.preferences.alarmRepeatMinutes.value.coerceAtLeast(0) * 60_000L
    // 0 = until dismissed carries over to the test, so it behaves exactly like a real alarm.
    private fun testTimeout(): Int = app.preferences.alarmTimeoutSeconds.value.let { if (it <= 0) 0 else it.coerceIn(10, 600) }

    private fun title(kind: Kind) = when (kind) {
        Kind.LOW -> "Low glucose"
        Kind.HIGH -> "High glucose"
        Kind.LOSS -> "Signal lost"
    }

    private fun valueText(mgdl: Double, unit: GlucoseUnit) = "${unit.format(mgdl)} ${unit.label}"
}

/**
 * Whether a new excursion of [kind] is still held back by a recent dismiss: only by one of the
 * same kind, and only within the repeat window. A dismissed High must never delay a Low (correct
 * a high with insulin and the low that follows is the alarm that matters most).
 */
internal fun dismissThrottles(
    kind: AlarmController.Kind,
    dismissedKind: AlarmController.Kind?,
    dismissMillis: Long,
    now: Long,
    repeatMillis: Long,
): Boolean =
    repeatMillis > 0 && dismissMillis > 0L && dismissedKind == kind && (now - dismissMillis) < repeatMillis

/**
 * Whether a Low / High excursion ([activeKind]) is over because the readings stopped under it:
 * the reading at [now] is the first since [previousReadingMillis] (0 = none before), and that is
 * at least [lossMillis] ago — the silence "Signal lost" is raised for. Until an in-range reading
 * arrives a Low stays one, dismissed or rung out; but through a silence that long the value may
 * have been back in range unseen (what the sensor backfills afterwards is not evaluated), and a
 * Low at its end is then a new one that has to ring. With the signal-loss alarm on, that alarm
 * already takes the excursion's place when the silence has lasted this long; this makes the
 * outcome the same with it off, or where its check comes late. (A restart draws the same line
 * after ten minutes, see restoreExcursion.) Whether the new excursion rings is still up to
 * [dismissThrottles].
 */
internal fun silenceEndsExcursion(
    activeKind: AlarmController.Kind?,
    previousReadingMillis: Long,
    now: Long,
    lossMillis: Long,
): Boolean =
    (activeKind == AlarmController.Kind.LOW || activeKind == AlarmController.Kind.HIGH) &&
        previousReadingMillis > 0L && now - previousReadingMillis >= lossMillis

/**
 * Whether a ring that is still up ([ringKind], null = not known; [ringIsTest]) belongs to an
 * earlier excursion than the one of [kind] that has just begun, and so has to end with it. Not a
 * test ring, and not one of unknown kind: when in doubt it keeps ringing.
 */
internal fun ringOfEarlierExcursion(
    ringKind: AlarmController.Kind?,
    ringIsTest: Boolean,
    kind: AlarmController.Kind,
): Boolean = !ringIsTest && ringKind != null && ringKind != kind

/**
 * Since when the absence of readings counts towards "Signal lost": the last reading
 * ([lastReadingMillis], 0 = none yet) — or, for a [sensor] in use that has not delivered since
 * its warm-up, the end of that warm-up. Until then a new sensor is legitimately silent, and from
 * there its first reading gets the same window as any other silence: also when the previous
 * sensor's marker was dropped with its deactivation, and for the first sensor ever. Nor sooner
 * than this process has seen that sensor in use ([sensorSeenMillis]): one taken over long after
 * its warm-up, or a process that has only just started, first needs the time to connect. A sensor
 * past its wear time has no readings due. 0 = nothing to wait for: no reading yet and no sensor
 * in use.
 *
 * A reading from before this sensor came into use was the previous one's
 * ([previousSensorsReading], 0 = no sensor before it in this process): however recent, it does
 * not make a sensor "delivered" that was started elsewhere and taken over while the old one was
 * still known — that one, too, gets its window from when it was first seen.
 */
internal fun lossCountsFrom(
    lastReadingMillis: Long,
    sensor: SensorInfo?,
    sensorSeenMillis: Long,
    now: Long,
    previousSensorsReading: Long = 0L,
): Long {
    val warmupEnd = sensor?.takeIf { it.activationMillis > 0L && !it.isExpired(now) }?.warmupEndMillis()
        ?: return lastReadingMillis
    // It has delivered: the plain rule, as for a sensor without a card.
    if (lastReadingMillis >= warmupEnd && lastReadingMillis > previousSensorsReading) return lastReadingMillis
    return maxOf(warmupEnd, sensorSeenMillis)
}

/**
 * When the first check of a silence is due: at its [deadline] — and now, once that has passed
 * without the check having run for this silence ([checked] false). A deadline that has passed
 * does not say the check ran: the watchdog's `delay` stands still while the CPU sleeps, and the
 * AlarmManager one-shot may come minutes late where it is inexact. A Dismiss or Snooze that arms
 * in between (from the watch, say) would cancel it, and neither the alarm nor the notification
 * would appear until the phone had been awake for the rest of the delay. 0 = it has run: from
 * there only a re-alert is left to wait for (see [lossRealertAt]) — so "now" is handed out until
 * the check has run, and not once more after it.
 */
internal fun firstLossCheckAt(deadline: Long, checked: Boolean, now: Long): Long = when {
    now < deadline -> deadline
    !checked -> now
    else -> 0L
}

/**
 * When a "Signal lost" that rang and was not dismissed is to be looked at next: once it is
 * settled whether a restored ring comes back ([ringRestartWaitMillis], 0 = settled), else at the
 * end of its snooze, else one repeat interval after it last rang — and in a minute when that has
 * passed, the watchdog's cadence while loss is pending. 0 = not at all: neither snoozed nor
 * repeating — or no sensor is left that should be sending ([sensorActive] false: it expired,
 * ended or was deactivated). The check decides nothing for such a sensor, so neither time would
 * ever move on: it would be a minute overdue for as long as that sensor stays, with a wake-up
 * each time. Another sensor coming into use arms the check afresh, for its own deadline.
 */
internal fun lossRealertAt(
    sensorActive: Boolean,
    ringRestartWaitMillis: Long,
    snoozeUntil: Long,
    lastFiredMillis: Long,
    repeatMillis: Long,
    now: Long,
): Long {
    if (!sensorActive) return 0L
    if (ringRestartWaitMillis > 0L) return now + ringRestartWaitMillis
    val due = when {
        snoozeUntil > 0L -> snoozeUntil
        repeatMillis > 0L -> lastFiredMillis + repeatMillis
        else -> return 0L
    }
    return if (due > now) due else now + 60_000L
}

/**
 * Whether a signal loss reported while [lossSensorId] was the sensor in use is another sensor's
 * than the one in use now ([sensorId]): it was replaced, with or without "Deactivate". Only where
 * both are known — with no sensor shown (null) then or now nothing says so, and what was reported
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
 * How much longer to wait for the alarm service's restart before taking a restored excursion's
 * ring for lost: only while the service has a ring saved that it has not brought back
 * ([savedRingPending]), and only for the first moments of the process ([sinceStartMillis]).
 * 0 = nothing to wait for.
 */
internal fun ringRestartWaitMillis(savedRingPending: Boolean, sinceStartMillis: Long): Long =
    if (savedRingPending) (AlarmController.RING_RESTART_GRACE_MS - sinceStartMillis).coerceAtLeast(0L) else 0L
