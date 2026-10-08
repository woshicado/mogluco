package de.mogluco.notification

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import de.mogluco.MoglucoApp
import de.mogluco.data.GlucoseUnit

/**
 * Decides *when* to raise a glucose alarm and drives [AlarmService] (which does the ringing).
 *
 * Evaluated on every reading: a value below/above the user's target range raises a Low/High alarm; a
 * gap with no readings raises a signal-loss alarm. The re-alert behaviour follows the user's rules:
 *  - Repeat interval 0 ⇒ no automatic repeat; only a *new* excursion (back in range, then out
 *    again) alarms again.
 *  - Dismiss ⇒ the current excursion stays silent no matter how long it lasts; it re-arms only when
 *    the value returns to range, and the next excursion is still throttled by the repeat interval.
 *  - Snooze ⇒ re-alerts once the reminder interval passes, but only if still out of range; a fresh
 *    excursion during the snooze alarms immediately.
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
    private var signalLossNotified = false    // a signal-loss NOTIFICATION is currently posted

    fun init(application: MoglucoApp) {
        if (started) return
        started = true
        app = application
        // The watchdog compares against the last reading time, which is process-local: after a
        // process restart it would read 0 and the "never had a sensor" guard would disarm signal
        // loss entirely until the app is opened. Seed from the persisted marker (and below from
        // the repository's history tail, which can be newer than the throttled write).
        lastReadingMillis = app.preferences.lastReadingEpochMillis.value
        lastReadingFlow.value = lastReadingMillis
        CoroutineScope(Dispatchers.Default).launch {
            val tail = runCatching {
                app.glucoseRepository.history.firstOrNull()?.lastOrNull()?.epochMillis
            }.getOrNull() ?: 0L
            seedLastReading(tail)
        }
        // Signal-loss watchdog: loss is the *absence* of readings, so it can't be detected on
        // arrival — but a minute poll (~1440 CPU wakeups/day) is wasteful when the deadline is
        // known. Sleep until the newest reading's loss deadline instead; a fresh reading or a
        // pref change restarts the wait (collectLatest). Only while loss is actually pending does
        // the loop fall back to a minute cadence, preserving the old repeat/snooze re-fire timing.
        CoroutineScope(Dispatchers.Default).launch {
            val prefs = app.preferences
            combine(
                lastReadingFlow,
                prefs.signalLossAlarm,
                prefs.signalLossNotification,
                prefs.signalLossMinutes,
            ) { last, alarm, notify, minutes -> Triple(last, alarm || notify, minutes) }
                .collectLatest { (last, enabled, minutes) ->
                    if (!enabled || last == 0L) return@collectLatest
                    while (true) {
                        val untilLoss =
                            last + minutes.coerceAtLeast(1) * 60_000L - System.currentTimeMillis()
                        if (untilLoss > 0) delay(untilLoss)
                        checkSignalLoss()
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
        if (signalLossNotified) {
            signalLossNotified = false
            WatchNotifications.cancelSignalLoss(context)
        }
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
        evaluate(context, kind, valueText(mgdl, prefs.unit.value))
    }

    /**
     * The dismiss/snooze/repeat state machine. [kind] null means in range — that resets the
     * excursion (and silences a ringing alarm, including signal loss, since a reading means the
     * signal is back).
     */
    private fun evaluate(context: Context, kind: Kind?, detail: String) {
        val now = System.currentTimeMillis()
        if (kind == null) {
            if (activeKind != null) {
                AlarmService.stop(context)
                activeKind = null
                dismissed = false
                snoozeUntil = 0L
            }
            return
        }
        val repeat = repeatMillis()
        if (activeKind != kind) {
            // A new excursion. Fire unless a recent dismiss is still throttling it within the repeat
            // window (L2: re-alert only once the repeat time has passed AND we left range again).
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
        // Ongoing same excursion.
        if (dismissed) return // stays dismissed until the value returns to range (L2)
        if (snoozeUntil > 0L) {
            if (now >= snoozeUntil) {
                snoozeUntil = 0L
                fire(context, kind, detail) // re-alert after the snooze, still out of range (L3)
            }
            return
        }
        // Not dismissed, not snoozed: auto-repeat unless the interval is 0 (L1: no repeat).
        if (repeat > 0 && now - lastFiredMillis >= repeat) {
            fire(context, kind, detail)
        }
    }

    private fun fire(context: Context, kind: Kind, detail: String) {
        AlarmService.start(context, title(kind), detail, app.preferences.alarmTimeoutSeconds.value)
        lastFiredMillis = System.currentTimeMillis()
    }

    @Synchronized
    private fun checkSignalLoss() {
        if (!started) return
        val prefs = app.preferences
        if (lastReadingMillis == 0L) return // never had a sensor; nothing to lose
        // A freshly activated sensor is silent for its 60-min warm-up; the stale last-reading time
        // belongs to the PREVIOUS sensor, so don't cry "Signal lost" while the new one warms up.
        if (app.glucoseRepository.currentSensor?.isWarmingUp() == true) return
        val lossMs = prefs.signalLossMinutes.value.coerceAtLeast(1) * 60_000L
        if (System.currentTimeMillis() - lastReadingMillis < lossMs) return
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
        AlarmService.start(context, "Test alarm", "This is how your alarm will sound and vibrate.", testTimeout())
    }

    @Synchronized
    fun onUserDismiss(context: Context) {
        // Stay silent for this excursion until the value returns to range; the next excursion is
        // throttled by the repeat interval (L2). The alarm UI already stopped the ring.
        dismissed = true
        lastDismissMillis = System.currentTimeMillis()
        snoozeUntil = 0L
        // Mirror the dismissal to the watch so its matching alarm silences too.
        runCatching { tk.glucodata.WatchLink.pushAlarmDismiss() }
    }

    /** The watch dismissed its alarm — silence ours and adopt the same dismissed state (no loop). */
    @Synchronized
    fun onRemoteDismiss(context: Context) {
        if (!started) return
        dismissed = true
        lastDismissMillis = System.currentTimeMillis()
        snoozeUntil = 0L
        AlarmService.stop(context)
    }

    @Synchronized
    fun onUserSnooze(context: Context) {
        // Re-alert after the reminder interval, only if still out of range (L3).
        val mins = app.preferences.reminderRepeatMinutes.value.coerceAtLeast(0)
        snoozeUntil = System.currentTimeMillis() + mins * 60_000L
        dismissed = false
    }

    @Synchronized
    fun onTimeout(context: Context) {
        // The ring auto-silenced after its duration. This is NOT a dismiss or snooze: leave the
        // excursion armed so the repeat interval (if any) re-alerts a persistent condition.
    }

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
