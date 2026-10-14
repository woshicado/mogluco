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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.mogluco.wear.WearApp
import java.util.concurrent.atomic.AtomicInteger

/**
 * The ringing half of the watch alarm (port of the phone's AlarmService, vibration-first):
 * loops a strong vibration waveform (and the system alarm tone if enabled), wakes the
 * screen, posts a full-screen high-importance notification with Dismiss/Snooze, honors the
 * "ring through DND" preference, and rings until dismissed unless a timeout is configured.
 */
class WearAlarmService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable {
        val ended = _ringing.value
        stopRinging(byTimeout = true)
        // After stopRinging, not from within: nothing that holds raiseLock calls the controller.
        // And not where the next ring is already on its way to this service (a repeat crossing
        // the timeout): that excursion has a ring, it has not rung out.
        if (ended?.test == false && startsUnderway.get() == 0) WearAlarmController.onTimeout(this, ended.kind)
    }
    // The direct launch can lose a race against a screen-state transition (waking from ambient)
    // even when it's permitted; retry while the alarm still rings and no surface came up.
    private val relaunch = Runnable {
        if (_ringing.value != null && !WearAlarmActivity.visible) launchAlarmScreen()
    }

    // While ringing with the surface gone (backgrounded or swiped away without a dismiss, or never
    // came up), keep trying to bring it back on a slow cadence. Armed for the whole ring. Only
    // while the screen is on: the surface turns the screen on, so relaunching it over a sleeping
    // or ambient display would wake the watch again and again for as long as the alarm rings. The
    // vibration doesn't need the screen, and the screen-on receiver below restores the surface the
    // moment the user raises the wrist.
    private val keepSurface = object : Runnable {
        override fun run() {
            if (_ringing.value == null) return
            // Also the heartbeat of the saved copy: its age says when the alarm was last known
            // to be on (see savedRingFresh). On this loop rather than a timer of its own — it
            // runs for the whole ring anyway and, counting uptime, never wakes a sleeping watch.
            stampSavedRinging(this@WearAlarmService)
            if (!WearAlarmActivity.visible && isScreenInteractive()) launchAlarmScreen()
            handler.postDelayed(this, SURFACE_RETRY_MS)
        }
    }

    // The platform cancels a non-system app's vibration when the screen turns off for most reasons
    // other than the plain timeout (power key, palm cover, wrist gesture, bedtime mode) — even with
    // USAGE_ALARM. A ringing alarm must survive that, so re-issue the waveform shortly after every
    // screen-off while we're still ringing. Restarting the pattern is harmless if it wasn't cut.
    private val rearmVibration = Runnable {
        vibrator?.let(::playWaveform)
        runCatching { if (rearmWakeLock?.isHeld == true) rearmWakeLock?.release() }
    }
    private var screenOffReceiver: BroadcastReceiver? = null

    // Keeps the CPU up across the short re-arm delay: after the initial 10 s screen lock expires
    // nothing else holds it, and the device may suspend before a 300 ms post runs.
    private val rearmWakeLock: PowerManager.WakeLock? by lazy {
        runCatching {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "juggluco:wearalarm-rearm")
                .apply { setReferenceCounted(false) }
        }.getOrNull()
    }

    private var title: String = "Glucose alarm"
    private var text: String = ""
    // The interruption filter this instance lifted for "Ring through DND" (or took over from a
    // ring that a process death cut off) and gives back when the alarm ends. Also kept in the
    // saved copy (KEY_DND_FILTER): the process can die between the two.
    private var previousInterruptionFilter: Int? = null
    // "Ring through DND" is off AND a DND/zen mode is active → this alarm respects DND by staying
    // SILENT (no sound, no vibration) but still shows the full-screen surface so it's visible.
    private var dndSuppressed: Boolean = false

    // The id of the newest start this instance was handed — a Dismiss / Snooze / Stop counts as
    // one, and so do a surface request and a restart by the system. The instance ends with it
    // (see stopRinging), never with a plain stopSelf().
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Sent by the alarm notification itself (its buttons, or swiping it away), see isRealRing.
        val byNotification = intent?.getBooleanExtra(EXTRA_BY_NOTIFICATION, false) == true
        // The alarm this request was sent for has gone, and a fallback alarm raised since is up
        // in its place (see fallbacksRaised): that one is not the request's to answer, so its
        // excursion is left alone here — and the alarm itself in stopRinging.
        val overtaken = intent != null && raisedSince(intent)
        // The same for a ring that got to this service ahead of the request: a Dismiss / Snooze
        // tapped on the alarm before the one that rings now (its start was already on its way
        // when the tap came) is not this one's answer. The ring stays, and so does what its own
        // start said about restarts. (Not ringing here, the other alarm is a fallback one
        // raised since: `overtaken`, above.)
        if (intent != null && sounding && answersAnotherRing(intent)) return START_STICKY
        // The three actions can land on an instance the system created just for them (nothing
        // was ringing in a service, or the process had restarted). That one never rings and
        // never goes foreground: stopRinging finds no ring to end and leaves with this start's id.
        when (intent?.action) {
            // Only a real alarm that is still ringing feeds the excursion state: stopping a test
            // ring, or a Dismiss arriving after the ring already ended, must not mark the current
            // excursion as dismissed (nor silence the phone).
            ACTION_DISMISS -> {
                if (!overtaken && isRealRing(byNotification)) {
                    WearAlarmController.onUserDismiss(this, realRingKind(this))
                }
                stopRinging(byTimeout = false, request = intent); return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                if (!overtaken && isRealRing(byNotification)) {
                    WearAlarmController.onUserSnooze(this, realRingKind(this))
                }
                stopRinging(byTimeout = false, request = intent); return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopRinging(byTimeout = false, request = intent); return START_NOT_STICKY
            }
            ACTION_SURFACE -> {
                // The alarm ended before this request landed (it races a remote dismiss), possibly
                // on an instance created just for it. Don't linger as a started sticky service:
                // the system would restart it after a process death and ring an alarm nobody
                // raised. stopSelf(startId) is a no-op if a newer start (a real alarm) is queued.
                if (_ringing.value == null) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                // The surface went to the background while ringing — bring it back soon, then
                // keep retrying slowly. Not a new alarm: nothing else changes.
                handler.removeCallbacks(keepSurface)
                handler.postDelayed(keepSurface, 1_000)
                // An instance created by this request belongs to a fallback alarm (no service
                // rang it, see postFallback) and never went through startRinging: give it the
                // screen-on receiver too, or a wrist raise only restores the surface on the slow
                // tick. No-op on the instance that rings. Sticky like a ring: after a process
                // death the restart rings the alarm postFallback saved, with its surface.
                registerScreenOffRearm()
                return START_STICKY
            }
        }
        // A restart after process death (START_STICKY) arrives with a null intent: restore the
        // alarm that was ringing from the copy saved below, so it rings on with its own texts and
        // duration — an alarm that was ringing must not go quiet. 0 = ring until dismissed (the
        // default, and what a restart falls back to when nothing was saved).
        val saved = if (intent == null) readSavedRing(this) else null
        if (saved != null && !saved.fresh) {
            // The alarm this copy describes was last on too long ago to be the one a restart is
            // owed (see savedRingFresh): don't ring an old alarm with an old value. What the
            // glucose is now, WearAlarmController decides with the next reading or loss check.
            android.util.Log.w(TAG, "not restoring an outdated alarm")
            // The alarm is not coming back, so neither is its end: if it left Do Not Disturb
            // lifted, this is where that is given back (while the record still counts as ours,
            // see liftedDndRecordValid).
            restoreInterruptionFilter()
            clearStaleSavedRinging(this)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        title = intent?.getStringExtra(EXTRA_TITLE) ?: saved?.title ?: title
        text = intent?.getStringExtra(EXTRA_TEXT) ?: saved?.text ?: text
        val timeoutSec = intent?.getIntExtra(EXTRA_TIMEOUT_SEC, 0) ?: saved?.timeoutSec ?: 0
        val test = intent?.getBooleanExtra(EXTRA_TEST, false) ?: saved?.test ?: false
        val kind = if (intent != null) WearAlarmController.kindOf(intent.getStringExtra(EXTRA_KIND)) else saved?.kind
        // Saved with the time it is on; a restored ring is on again (keepSurface re-stamps it).
        if (intent != null) {
            saveRinging(this, title, text, timeoutSec, test, kind, previousInterruptionFilter)
        } else {
            stampSavedRinging(this)
        }
        // Publish the ringing state BEFORE any surface can observe it: MainActivity mirrors it to
        // the full-screen activity whenever the app is opened, and the activity closes on null.
        _ringing.value = RingingAlarm(
            title, text, restored = intent == null, test = test, kind = kind,
            // A copy that only its notification vouched for (adoptPostedAlarm, which made it
            // count as fresh above): ringing again does not make the alarm any newer.
            aged = saved?.aged == true,
        )
        sounding = true
        // Only now that the alarm shows there: no moment in which neither says so (see ringUpOrPending).
        if (intent != null) startsUnderway.updateAndGet { (it - 1).coerceAtLeast(0) }

        // Decide DND handling up front. "Ring through DND" OFF + a DND mode active → show the
        // alarm silently (no sound/vibration) but still display the full-screen surface. Our
        // sound/vibration ride the alarm stream (USAGE_ALARM), which the default "Priority" DND
        // passes on its own, so without this gate the toggle did nothing. ON → force through
        // (startRinging lifts the interruption filter).
        dndSuppressed = computeDndSuppressed()

        ensureChannel(this)
        // A restart after process death is a start from the background, which the system may
        // refuse (ForegroundServiceStartNotAllowedException) unless the app is exempt from battery
        // optimisation — the likely case for an instance that only kept a fallback alarm's
        // surface, which exists because that very start was refused. Uncaught that would crash
        // the process instead of ringing — so ring anyway and post the alarm notification
        // (Dismiss / Snooze, full-screen intent) as a plain one; stopRinging cancels it either way.
        val notification = buildNotification()
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.onFailure {
            android.util.Log.w(TAG, "startForeground refused; ringing without it", it)
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification) }
        }
        startRinging()
        // The notification carries a full-screen intent, but Wear OS frequently degrades that to a
        // heads-up (nothing shows until the notification is opened) — especially when the screen is
        // off. Launch the alarm surface directly from this foreground service so the user sees what's
        // wrong and why it's vibrating without opening notifications. Best-effort: on the off chance
        // the platform blocks the background activity start, the full-screen intent remains the path.
        // Shown even when DND-suppressed — the alarm is silent then, but still visible.
        launchAlarmScreen()
        handler.removeCallbacks(relaunch)
        RELAUNCH_DELAYS_MS.forEach { handler.postDelayed(relaunch, it) }
        // From here on the slow loop owns the surface, however it gets lost (see keepSurface).
        handler.removeCallbacks(keepSurface)
        handler.postDelayed(keepSurface, SURFACE_RETRY_MS)
        handler.removeCallbacks(autoStop)
        if (timeoutSec > 0) handler.postDelayed(autoStop, timeoutSec * 1000L)
        return START_STICKY
    }

    /**
     * True when the alarm should stay silent for DND: "Ring through DND" is off AND a DND/zen mode is
     * currently active (any filter other than ALL). Reading the filter needs no special permission.
     */
    private fun computeDndSuppressed(): Boolean {
        if (WearApp.from(this).preferences.alarmOverrideDnd.value) return false
        return runCatching {
            val f = getSystemService(NotificationManager::class.java).currentInterruptionFilter
            f != NotificationManager.INTERRUPTION_FILTER_ALL &&
                f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }.getOrDefault(false)
    }

    private fun startRinging() {
        val prefs = WearApp.from(this).preferences

        if (dndSuppressed) {
            // DND active + "Ring through DND" off: stay silent (no sound/vibration below) but still
            // wake the screen and show the full-screen surface. No filter lift — we're respecting DND.
            android.util.Log.i(TAG, "DND active and 'Ring through DND' off — showing silently")
        }

        // "Ring through DND" ON: momentarily lift the interruption filter (restored when the alarm
        // stops) so the alarm forces through even a DND mode that silences alarms (Bedtime /
        // Total-silence / alarms-disallowed) — the channel bypass flag can't do that, it governs
        // notification alerting, not our alarm stream. Requires notification-policy access. (The
        // previous build skipped this on API ≥ 35, making the override a no-op on modern Wear OS.)
        if (prefs.alarmOverrideDnd.value && previousInterruptionFilter == null) {
            runCatching {
                val nm = getSystemService(NotificationManager::class.java)
                when {
                    !nm.isNotificationPolicyAccessGranted ->
                        android.util.Log.w(TAG, "DND override on, but notification-policy access not granted")
                    nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL -> {
                        val current = nm.currentInterruptionFilter
                        previousInterruptionFilter = current
                        // On record before it is lifted: if the process dies while this rings,
                        // the ring that is restored finds the filter already off and could not
                        // tell what to give back — Do Not Disturb would stay off for the night.
                        saveDndFilter(this, current)
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    }
                    // Already off — by a ring that a process death cut off, if it left a record:
                    // this alarm continues it and gives the filter back when it ends.
                    else -> previousInterruptionFilter = savedDndFilter(this)
                }
            }.onFailure { android.util.Log.w(TAG, "DND override failed", it) }
        }

        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "juggluco:wearalarm",
            ).apply { runCatching { acquire(10_000L) } }
        }

        // Vibration first — the reliable attention-getter on the wrist. Alarm-usage
        // attributes keep it exempt from DND/ringer policies. Skipped when DND-suppressed (silent).
        if (!dndSuppressed && prefs.alarmVibrate.value && vibrator == null) {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            vibrator = vib
            playWaveform(vib)
        }
        // Screen on/off handling (vibration re-arm + surface restore) applies to every ring,
        // including a silent DND-suppressed one that still needs its surface.
        registerScreenOffRearm()

        if (!dndSuppressed && prefs.alarmSound.value && player == null) {
            val uri: Uri? = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            player = uri?.let { createLoopingPlayer(it) }
        }
    }

    /** Start (or restart) the repeating alarm waveform. */
    private fun playWaveform(vib: Vibrator) {
        runCatching {
            val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, 0)
            vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        }.onFailure { android.util.Log.w(TAG, "alarm vibration failed", it) }
    }

    private fun registerScreenOffRearm() {
        if (screenOffReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // A silent ring (DND, or sound and vibration off) lets the watch sleep, and
                // keepSurface stands still with it: the screen changing state is the sign of
                // life then.
                if (_ringing.value != null) stampSavedRinging(context)
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        // Let the platform's screen-off cancellation land first, then restart the
                        // pattern — under a short wakelock so the delayed post actually runs.
                        runCatching { rearmWakeLock?.acquire(2_000L) }
                        handler.removeCallbacks(rearmVibration)
                        handler.postDelayed(rearmVibration, REARM_DELAY_MS)
                    }
                    // Wrist raised / screen woken while ringing: put the alarm surface up.
                    Intent.ACTION_SCREEN_ON ->
                        if (_ringing.value != null && !WearAlarmActivity.visible) launchAlarmScreen()
                }
            }
        }
        // Screen on/off are protected system broadcasts; a runtime receiver needs no export flag
        // for them, but API 33+ requires the flag argument, and NOT_EXPORTED is the safe choice.
        runCatching {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
            ContextCompat.registerReceiver(
                this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenOffReceiver = receiver
        }.onFailure { android.util.Log.w(TAG, "screen-off re-arm unavailable", it) }
    }

    private fun unregisterScreenOffRearm() {
        handler.removeCallbacks(rearmVibration)
        handler.removeCallbacks(keepSurface)
        runCatching { if (rearmWakeLock?.isHeld == true) rearmWakeLock?.release() }
        screenOffReceiver?.let { r -> runCatching { unregisterReceiver(r) } }
        screenOffReceiver = null
    }

    private fun launchAlarmScreen() {
        // From the ringing state, not this instance's fields: an instance created only to restore
        // the surface of a fallback alarm (no service ran for it) never received its texts.
        val alarm = _ringing.value ?: return
        runCatching { startActivity(alarmScreenIntent(this, alarm.title, alarm.text)) }
            .onFailure { android.util.Log.w(TAG, "direct alarm-screen launch failed", it) }
    }

    /** Screen fully on (not off, not ambient). Assumed on when the state can't be read. */
    private fun isScreenInteractive(): Boolean =
        runCatching { getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(true)

    /**
     * The interruption filter to give back now that the alarm ends, and forget it: the one this
     * instance holds, else the one on record from a ring that a process death cut off (an
     * instance that only receives the Dismiss never rang). Null = nothing was lifted.
     */
    private fun takeLiftedFilter(): Int? {
        val saved = takeSavedDndFilter(this)
        return (previousInterruptionFilter ?: saved).also { previousInterruptionFilter = null }
    }

    private fun restoreInterruptionFilter(previous: Int? = takeLiftedFilter()) {
        if (previous != null) giveBackInterruptionFilter(this, previous)
    }

    private fun createLoopingPlayer(uri: Uri): MediaPlayer? {
        val mp = MediaPlayer()
        return runCatching {
            mp.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@WearAlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrElse {
            runCatching { mp.release() }
            null
        }
    }

    /**
     * Whether a Dismiss / Snooze refers to a real alarm: one is ringing and it is not a test. The
     * saved copy stands in where the ringing state is already gone — endAlarm clears it before the
     * request gets here (and vouches for the copy as it does), and after a process restart it is
     * only back once the ring is restored. Only while the copy can still be the alarm that was
     * cut off (see [savedRingFresh]) — or the request comes from the alarm notification itself
     * ([byNotification]): that was on display until now, whatever the age of the copy, and the
     * copy is the alarm it showed (every post writes the copy first). A swipe has already taken
     * the notification down by the time a process started for it looks (see
     * [adoptPostedAlarm]), so its request is the only word left. Which excursion a copy that
     * old stands for is another matter, see [realRingKind].
     */
    private fun isRealRing(byNotification: Boolean): Boolean {
        _ringing.value?.let { return !it.test }
        return readSavedRing(this)?.let { (it.fresh || byNotification) && !it.test } == true
    }

    /**
     * Stop the sound/vibration and leave the service — unless a newer start is already queued
     * behind the one this ends with (a new alarm raised right after a Stop / Dismiss / Snooze, or
     * while the timeout ran out). `stopSelf()` without an id takes the service down regardless:
     * the queued alarm would ring for a moment on an instance about to be destroyed, and where
     * that start was a `startForegroundService` on a service that is not foreground, the system
     * kills the whole process for the `startForeground` it never got. `stopSelf(id)` only stops
     * when `id` is the newest start; otherwise the instance stays and the queued start finds it
     * as new: everything a ring sets up is undone here, and [onStartCommand] sets all of it up
     * again, `startForeground` included.
     *
     * [request] = the Stop / Dismiss / Snooze this is for (null: the timeout). A fallback alarm
     * raised after it was sent (see [fallbacksRaised]) is not the alarm it ends: this instance
     * goes quiet and leaves, and that alarm keeps its state, its saved copy and its notification.
     * Under [raiseLock], so that none of the three is taken from an alarm that is being raised
     * on another thread just now.
     */
    private fun stopRinging(byTimeout: Boolean, request: Intent? = null): Unit = synchronized(raiseLock) {
        val ended = request == null || !raisedSince(request)
        if (ended) _ringing.value = null
        sounding = false
        // Read before the copy goes: it also holds the lifted Do Not Disturb filter.
        val liftedFilter = takeLiftedFilter()
        // Nothing rings any more, so there is nothing for a restart to restore.
        if (ended) clearSavedRinging(this)
        handler.removeCallbacks(relaunch)
        handler.removeCallbacks(autoStop)
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
        unregisterScreenOffRearm()
        runCatching { vibrator?.cancel() }
        vibrator = null
        restoreInterruptionFilter(liftedFilter)
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        // The notification of a fallback alarm that is left up has the same id: where it took
        // the place of this instance's own, removing that one would take the alarm's.
        ServiceCompat.stopForeground(
            this,
            if (ended) ServiceCompat.STOP_FOREGROUND_REMOVE else ServiceCompat.STOP_FOREGROUND_DETACH,
        )
        // Also clear a notification posted OUTSIDE foreground mode (the postFallback path, when
        // the FGS start itself was blocked) — stopForeground doesn't remove that one.
        if (ended) runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        super.onDestroy()
        // stopRinging and endAlarm clear the ringing state before the instance goes, so a real
        // alarm that this instance still had in progress did not end through Dismiss, Snooze,
        // Stop or its timeout: the system stopped the service under it (see below). Nor is it an
        // alarm raised right behind one of those: stopRinging leaves the instance to a start
        // that is queued, and one issued later gets an instance of its own, after this one.
        val cutOff = if (sounding) _ringing.value?.takeIf { !it.test } else null
        sounding = false
        // Nothing of this instance may act on a later alarm: its timeout would end that one.
        handler.removeCallbacks(relaunch)
        handler.removeCallbacks(autoStop)
        // Every way an alarm ends drops its saved copy first (stopRinging, endAlarm). While the
        // copy is there, the system stopped this instance under an alarm that is still on
        // display: the helper instance of a fallback alarm (ACTION_SURFACE — a plain background
        // service, stopped after about a minute idle), or a ring that was refused foreground
        // state. The notification stays up, so the state stays with it: the surface closes on
        // null, and neither a tap on the notification nor opening the app would lead to it.
        // The copy's age is not asked here: this process only has a ringing state for an alarm
        // whose copy it wrote itself, found fresh, or took that very state from (see
        // adoptPostedAlarm), so the copy of some other, earlier alarm
        // cannot hold the state up — and the alarm's own copy may well be old by now (a fallback
        // alarm has no ring to re-stamp it; the watch may have slept under this instance).
        // Under the lock: a fallback alarm raised just now has its state before its copy.
        synchronized(raiseLock) { if (!hasSavedRinging(this)) _ringing.value = null }
        unregisterScreenOffRearm()
        runCatching { player?.release() }
        runCatching { vibrator?.cancel() }
        restoreInterruptionFilter()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        // The alarm stays on display, but it has gone quiet, and its excursion must not count
        // as answered. Last, now that nothing claims a ring in progress any more — the
        // controller looks at exactly that.
        if (cutOff != null) {
            android.util.Log.w(TAG, "alarm cut off while ringing")
            WearAlarmController.onRingCutOff(this, cutOff.kind)
        }
    }

    private fun buildNotification(): Notification {
        fun actionIntent(action: String) = PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, WearAlarmService::class.java).setAction(action).putExtra(EXTRA_BY_NOTIFICATION, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val fullScreen = PendingIntent.getActivity(
            this,
            1,
            alarmScreenIntent(this, title, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // The full-screen surface shows in both cases (DND-suppressed just makes it silent). The
        // notification itself carries no sound/vibration — the channel sets those to null and the
        // service drives its own — so keeping it here leaks no sound through DND.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(de.mogluco.wear.R.drawable.ic_glucose)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            // Wear OS frequently degrades the full-screen intent to a heads-up pop; make tapping
            // that pop (not just its actions) open the full-screen surface, where the crown
            // dismisses — instead of the user having to dig into the notification's buttons.
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, "Snooze", actionIntent(ACTION_SNOOZE))
            .addAction(0, "Dismiss", actionIntent(ACTION_DISMISS))
            .setDeleteIntent(actionIntent(ACTION_DISMISS))
            .build()
    }

    /**
     * What a ringing alarm shows: its title ("Low glucose") and detail ("3.4 mmol/L"). [restored]
     * marks a ring brought back by a service restart after process death: WearAlarmController's
     * excursion state may have died with the process, so it checks this to still end the ring
     * once the value is back in range. [test] marks a "Test alarm" from settings: stopping it
     * leaves the excursion state and the phone alone. [kind] is which alarm it is, or null where
     * that is not known (a test, a restart with nothing saved): WearAlarmController takes the
     * excursion from it when a Dismiss / Snooze lands on a restored ring whose excursion it did
     * not restore. An alarm taken over from its notification at process start (see
     * [adoptPostedAlarm]) is [restored] too; [aged] then says that its copy was older than
     * [savedRingFresh] allows — it is on display, but which excursion it stands for by now is
     * not known (see [realRingKind]).
     */
    data class RingingAlarm(
        val title: String,
        val text: String,
        val restored: Boolean = false,
        val test: Boolean = false,
        val kind: WearAlarmController.Kind? = null,
        val aged: Boolean = false,
    )

    /**
     * The copy of the alarm in progress as saved for a restart; [fresh] see [savedRingFresh],
     * [aged] see [RingingAlarm.aged] (kept in the copy: the stamp no longer tells, and a Dismiss
     * from the surface reads the copy, see endAlarm).
     */
    private class SavedRing(
        val title: String,
        val text: String,
        val timeoutSec: Int,
        val test: Boolean,
        val kind: WearAlarmController.Kind?,
        val fresh: Boolean,
        val aged: Boolean,
    )

    companion object {
        private const val TAG = "WearAlarmService"
        private const val CHANNEL_ID = "wear_glucose_alarm"

        /**
         * The ringing alarm's title/text/timeout/test flag/kind, kept only while it rings (restart
         * restore), with the time the alarm was last known to be on ([KEY_ALIVE_AT]), the
         * interruption filter it lifted, if any ([KEY_DND_FILTER]), and whether it was taken
         * over from a copy that had gone out of date ([KEY_AGED], see [RingingAlarm.aged]).
         */
        private const val RINGING_PREFS = "wear_alarm_ringing"
        private const val KEY_ALIVE_AT = "alive_at"
        private const val KEY_DND_FILTER = "dnd_filter"
        private const val KEY_AGED = "aged"

        // The copy is written off the main thread too (postFallback, endAlarm). What checks it
        // and then changes it does so under this lock, so that dropping an outdated copy can
        // never take a newer one with it that was written in between.
        private val savedLock = Any()

        private fun saveRinging(
            context: Context,
            title: String,
            text: String,
            timeoutSec: Int,
            test: Boolean,
            kind: WearAlarmController.Kind?,
            liftedFilter: Int? = null,
        ) {
            synchronized(savedLock) {
                runCatching {
                    val prefs = context.getSharedPreferences(RINGING_PREFS, Context.MODE_PRIVATE)
                    val edit = prefs.edit()
                    // The copy may still hold the filter an earlier ring lifted and never gave
                    // back (its process died). Checked against that ring's stamp before the new
                    // one below vouches for it: an outdated record must not become this alarm's.
                    // [liftedFilter] = the instance that rings holds it; that needs no vouching.
                    val now = System.currentTimeMillis()
                    when {
                        liftedFilter != null -> edit.putInt(KEY_DND_FILTER, liftedFilter)
                        !liftedDndRecordValid(prefs.getLong(KEY_ALIVE_AT, 0L), now) -> edit.remove(KEY_DND_FILTER)
                    }
                    edit
                        .putString(EXTRA_TITLE, title)
                        .putString(EXTRA_TEXT, text)
                        .putInt(EXTRA_TIMEOUT_SEC, timeoutSec)
                        .putBoolean(EXTRA_TEST, test)
                        .putString(EXTRA_KIND, kind?.name)
                        .remove(KEY_AGED) // a new alarm
                        .putLong(KEY_ALIVE_AT, now)
                        .apply()
                }
            }
        }

        /** Note in the saved copy which interruption filter the ringing alarm lifted. */
        private fun saveDndFilter(context: Context, filter: Int) {
            synchronized(savedLock) {
                runCatching {
                    context.getSharedPreferences(RINGING_PREFS, Context.MODE_PRIVATE).edit()
                        .putInt(KEY_DND_FILTER, filter).apply()
                }
            }
        }

        /**
         * The interruption filter a ring lifted and has not given back, or null: none on record,
         * or the record no longer counts as ours (see [liftedDndRecordValid]).
         */
        private fun savedDndFilter(context: Context): Int? = runCatching {
            val prefs = context.getSharedPreferences(RINGING_PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_DND_FILTER)) return@runCatching null
            prefs.getInt(KEY_DND_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
                .takeIf { liftedDndRecordValid(prefs.getLong(KEY_ALIVE_AT, 0L), System.currentTimeMillis()) }
        }.getOrNull()

        /** [savedDndFilter], and drop the record: it is given back now, or no longer ours. */
        private fun takeSavedDndFilter(context: Context): Int? = synchronized(savedLock) {
            val filter = savedDndFilter(context)
            runCatching {
                val prefs = context.getSharedPreferences(RINGING_PREFS, Context.MODE_PRIVATE)
                if (prefs.contains(KEY_DND_FILTER)) prefs.edit().remove(KEY_DND_FILTER).apply()
            }
            filter
        }

        /**
         * Set the interruption filter back to [previous], the one an alarm lifted. Only while it
         * is still as the alarm left it: if Do Not Disturb is on again, somebody else decided.
         */
        private fun giveBackInterruptionFilter(context: Context, previous: Int) {
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm.isNotificationPolicyAccessGranted &&
                    nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
                ) {
                    nm.setInterruptionFilter(previous)
                }
            }
        }

        /**
         * The alarm the copy describes is on right now: re-stamp it. Only for callers that know
         * so from this process (it rings here, or its state is still set).
         */
        private fun stampSavedRinging(context: Context) {
            synchronized(savedLock) {
                runCatching {
                    val prefs = context.getSharedPreferences(RINGING_PREFS, Context.MODE_PRIVATE)
                    if (prefs.contains(EXTRA_TITLE)) {
                        prefs.edit().putLong(KEY_ALIVE_AT, System.currentTimeMillis()).apply()
                    }
                }
            }
        }

        /** The saved copy, or null when nothing is saved. Check [SavedRing.fresh] before trusting it. */
        private fun readSavedRing(context: Context): SavedRing? {
            val prefs = runCatching { context.getSharedPreferences(RINGING_PREFS, Context.MODE_PRIVATE) }.getOrNull()
                ?: return null
            val title = prefs.getString(EXTRA_TITLE, null) ?: return null
            return SavedRing(
                title = title,
                text = prefs.getString(EXTRA_TEXT, null).orEmpty(),
                timeoutSec = prefs.getInt(EXTRA_TIMEOUT_SEC, 0),
                test = prefs.getBoolean(EXTRA_TEST, false),
                kind = WearAlarmController.kindOf(prefs.getString(EXTRA_KIND, null)),
                fresh = savedRingFresh(prefs.getLong(KEY_ALIVE_AT, 0L), System.currentTimeMillis()),
                aged = prefs.getBoolean(KEY_AGED, false),
            )
        }

        /**
         * Whether an alarm was raised and has not ended yet (its copy goes with it, see
         * stopRinging). Whatever its age — only for [onDestroy], which asks about an alarm of
         * this process, and [adoptPostedAlarm], which has the notification's word for it.
         */
        private fun hasSavedRinging(context: Context): Boolean = runCatching {
            context.getSharedPreferences(RINGING_PREFS, Context.MODE_PRIVATE).contains(EXTRA_TITLE)
        }.getOrDefault(false)

        private fun clearSavedRinging(context: Context) {
            synchronized(savedLock) {
                runCatching { context.getSharedPreferences(RINGING_PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
            }
        }

        /** Drop the copy if it is (still) an outdated one — not one an alarm raised meanwhile wrote. */
        private fun clearStaleSavedRinging(context: Context) {
            synchronized(savedLock) {
                if (readSavedRing(context)?.fresh == false) clearSavedRinging(context)
            }
        }

        /**
         * Which real (non-test) alarm a Dismiss / Snooze refers to: the one ringing, or — where
         * the ringing state is gone (see [isRealRing]) — the one saved. Null when there is none,
         * or its kind is not known. Null also for an alarm that only its notification vouches
         * for, with a copy older than [savedRingFresh] allows: the Dismiss / Snooze is real, but
         * the excursion is not taken from an alarm that old. Since it was raised the value may
         * have been back in range unseen (WearAlarmController.restoreExcursion draws the same
         * line), and then the next reading out of range must ring.
         */
        fun realRingKind(context: Context): WearAlarmController.Kind? {
            _ringing.value?.let { return if (it.test || it.aged) null else it.kind }
            return readSavedRing(context)?.takeIf { it.fresh && !it.test && !it.aged }?.kind
        }

        /**
         * At process start (WearAlarmController.load — before anything is evaluated, and before
         * a request of the notification is handled): an alarm notification that is still posted
         * has outlived the process that raised its alarm. A fallback alarm (see [postFallback])
         * or a ring the system refused the foreground has no service the system would restart,
         * so nothing brings the state back, and once the copy is older than [savedRingFresh]
         * allows nothing ties the notification to an alarm any more: its Dismiss would not
         * count, a value back in range would leave it standing, and opening it would lead
         * nowhere. So the notification vouches for the copy — every post of it writes the copy
         * first — and the state is taken from there, as [RingingAlarm.restored]: on display,
         * not sounding (a restart of the service still rings it, once; see
         * WearAlarmController.evaluate for what is rung again).
         *
         * Without the notification nothing changes: a copy counts while it is fresh, and an
         * older one is left to whatever drops it today (it still holds the lifted Do Not
         * Disturb filter, and a swiped notification's request refers to it, see [isRealRing]).
         */
        fun adoptPostedAlarm(context: Context) {
            if (_ringing.value != null || !hasSavedRinging(context)) return
            // The app's own notifications: needs no permission.
            val posted = runCatching {
                context.getSystemService(NotificationManager::class.java)
                    .activeNotifications.any { it.id == NOTIF_ID }
            }.getOrDefault(false)
            if (!posted) return
            synchronized(savedLock) {
                val saved = readSavedRing(context) ?: return
                // Once aged, always: the stamp below makes the copy count as fresh from here on.
                val aged = saved.aged || !saved.fresh
                // On again, so stamped like a restored ring. The lifted-filter record is checked
                // against the old stamp first, as in saveRinging: the new one must not vouch
                // for a record that no longer counts as ours.
                runCatching {
                    val prefs = context.getSharedPreferences(RINGING_PREFS, Context.MODE_PRIVATE)
                    val now = System.currentTimeMillis()
                    val edit = prefs.edit()
                    if (!liftedDndRecordValid(prefs.getLong(KEY_ALIVE_AT, 0L), now)) edit.remove(KEY_DND_FILTER)
                    if (aged) edit.putBoolean(KEY_AGED, true)
                    edit.putLong(KEY_ALIVE_AT, now).apply()
                }
                _ringing.compareAndSet(
                    null,
                    RingingAlarm(
                        saved.title, saved.text,
                        restored = true, test = saved.test, kind = saved.kind, aged = aged,
                    ),
                )
            }
        }

        // Non-null exactly while the alarm is ringing. MainActivity observes it to bring up the
        // full-screen surface whenever the app is opened mid-alarm (any entry point: launcher,
        // complication, tile, ongoing chip); WearAlarmActivity observes it to close itself when
        // the alarm ends (dismiss from the phone, back-in-range, timeout).
        private val _ringing = kotlinx.coroutines.flow.MutableStateFlow<RingingAlarm?>(null)
        val ringing: kotlinx.coroutines.flow.StateFlow<RingingAlarm?> get() = _ringing

        // An instance has the alarm in [ringing] in progress: it rings it (or shows it silently
        // under Do Not Disturb), from its start until stopRinging or onDestroy. The ringing
        // state alone does not say so — it also stands for an alarm that is only on display: a
        // fallback alarm (no service rang it), one the system stopped the service under, or one
        // taken over from its notification at process start (adoptPostedAlarm).
        @Volatile
        private var sounding = false

        /** Whether a real (non-test) alarm is in progress in the service, not merely on display. */
        fun realRingSounding(): Boolean = sounding && _ringing.value?.test == false

        // Alarms handed to the system by [start] that onStartCommand has not got to yet: the
        // main thread may be busy for a while (app start), and until then [ringing] is still null.
        private val startsUnderway = AtomicInteger(0)

        /**
         * Whether a [stop] has anything to end: an alarm is on its way to the service, one is up
         * (ringing, or only on display), an instance still has a ring in progress, or a copy is
         * saved — whatever its age: the stop is also what drops it, and what gives back the Do
         * Not Disturb filter noted in it. Where none of that holds, the stop would only make the
         * system create the service to stop it again.
         */
        fun ringUpOrPending(context: Context): Boolean =
            // In this order: a start is counted until its alarm shows in the ringing state.
            startsUnderway.get() > 0 || _ringing.value != null || sounding || hasSavedRinging(context)

        // A ring reaches the service in the order of its start, behind every Stop / Dismiss /
        // Snooze sent before it. A fallback alarm (see postFallback) does not: it is up the
        // moment it is posted, also while such a request is still waiting for the main thread —
        // and that request, handled a moment later, would take its state, its saved copy and its
        // notification with it: an alarm ended by the Stop of the one before. So the fallback
        // alarms are counted, and a request carries the count of when it was sent
        // ([EXTRA_RAISED]): the service leaves alone what was raised after it (see stopRinging).
        private val fallbacksRaised = AtomicInteger(0)

        // Raising a fallback alarm, ending an alarm up front (endAlarm) and ending it in the
        // service (stopRinging) each happen as a whole under this lock, so that the count above
        // puts them in one order. Taken before savedLock, never the other way round, and
        // nothing that holds it calls into WearAlarmController.
        private val raiseLock = Any()

        /** Whether a fallback alarm was raised after [request] (a Stop / Dismiss / Snooze) was sent. */
        private fun raisedSince(request: Intent): Boolean =
            // The notification's own requests carry no count: they refer to what it shows. Nor
            // does a count from a previous process say anything (it would be the higher one).
            request.getIntExtra(EXTRA_RAISED, Int.MAX_VALUE) < fallbacksRaised.get()
        private const val NOTIF_ID = 0xA1
        val VIBRATION_PATTERN = longArrayOf(0, 400, 250, 400, 250, 700)
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TIMEOUT_SEC = "timeout"
        private const val EXTRA_TEST = "test"
        private const val EXTRA_KIND = "kind"

        /** On the notification's own Dismiss / Snooze requests (buttons, swipe), see [isRealRing]. */
        private const val EXTRA_BY_NOTIFICATION = "by_notification"

        /** On a Stop / Dismiss / Snooze sent by [endAlarm]: [fallbacksRaised] at that time. */
        private const val EXTRA_RAISED = "raised"

        /** On a Dismiss / Snooze sent by [endAlarm]: the [ringTag] of the alarm it was tapped on. */
        private const val EXTRA_TAPPED = "tapped"

        // What tells one alarm from the one raised after it: a test from a real alarm, and a
        // real one by its kind. A repeat of the same excursion is the same alarm in this sense.
        private fun ringTag(ring: RingingAlarm): String = "${ring.test}|${ring.kind?.name.orEmpty()}"

        /**
         * Whether [request] (a Dismiss / Snooze) was tapped on another alarm than the one that is
         * up now. A new alarm is handed to the system from the thread its reading arrives on; a
         * tap in the moment before the main thread gets to it is queued behind it, and would
         * otherwise answer the new alarm: a Low dismissed — here and on the phone — by a tap
         * that was meant for "Signal lost". The notification's own buttons carry no tag (they
         * belong to whatever it shows).
         */
        private fun answersAnotherRing(request: Intent): Boolean {
            if (request.action != ACTION_DISMISS && request.action != ACTION_SNOOZE) return false
            val tapped = request.getStringExtra(EXTRA_TAPPED) ?: return false
            return _ringing.value?.let { ringTag(it) != tapped } == true
        }
        const val ACTION_DISMISS = "de.mogluco.wear.ALARM_DISMISS"
        const val ACTION_SNOOZE = "de.mogluco.wear.ALARM_SNOOZE"
        const val ACTION_STOP = "de.mogluco.wear.ALARM_STOP"
        private const val ACTION_SURFACE = "de.mogluco.wear.ALARM_SURFACE"

        /** Slow retry cadence for bringing a lost alarm surface back while ringing (screen on). */
        private const val SURFACE_RETRY_MS = 30_000L

        /** Delay after a screen-off before re-issuing the waveform (after the platform cancels it). */
        private const val REARM_DELAY_MS = 300L

        /** Direct-launch retry points after the alarm starts (screen-transition races). */
        private val RELAUNCH_DELAYS_MS = longArrayOf(2_000, 6_000)

        /** The one intent that opens the full-screen surface, shared by every path that can. */
        fun alarmScreenIntent(context: Context, title: String, text: String): Intent =
            Intent(context, WearAlarmActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)

        /**
         * [test] = a "Test alarm" from settings, which ends without touching excursion state or
         * the phone. [kind] = which alarm it is (see [RingingAlarm]).
         */
        fun start(
            context: Context,
            title: String,
            text: String,
            timeoutSeconds: Int,
            test: Boolean = false,
            kind: WearAlarmController.Kind? = null,
        ) {
            val intent = Intent(context, WearAlarmService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_TIMEOUT_SEC, timeoutSeconds)
                .putExtra(EXTRA_TEST, test)
                .putExtra(EXTRA_KIND, kind?.name)
            startsUnderway.incrementAndGet()
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    // Counted, and raised as a whole (see fallbacksRaised): a Stop / Dismiss /
                    // Snooze sent before this must not end it.
                    synchronized(raiseLock) {
                        fallbacksRaised.incrementAndGet()
                        postFallback(context, title, text, timeoutSeconds, test, kind)
                    }
                    // Only now that the alarm shows in the ringing state (see ringUpOrPending).
                    startsUnderway.updateAndGet { (it - 1).coerceAtLeast(0) }
                }
        }

        /** The alarm surface was backgrounded while still ringing — ask the service to restore it. */
        fun requestSurface(context: Context) {
            runCatching {
                context.startService(Intent(context, WearAlarmService::class.java).setAction(ACTION_SURFACE))
            }
        }

        /** Silence without snoozing (condition resolved on its own). */
        fun stop(context: Context) = endAlarm(context, ACTION_STOP)

        fun dismiss(context: Context) = endAlarm(context, ACTION_DISMISS)

        fun snooze(context: Context) = endAlarm(context, ACTION_SNOOZE)

        /**
         * Clear the observable state and the notification up front, THEN tell the service. The
         * direct clears matter on the fallback path (no service is running there to do either)
         * and when the startService itself is rejected from the background — the alarm must never
         * look active after the user (or the phone, or a back-in-range value) ended it.
         */
        private fun endAlarm(context: Context, action: String): Unit = synchronized(raiseLock) {
            // The service reads from the saved copy what this request refers to (isRealRing,
            // realRingKind), and only trusts a recently stamped one. While the state still says
            // the alarm is on, vouch for the copy: nothing re-stamps it under a fallback alarm,
            // or under a silent ring the watch slept through.
            if (_ringing.value != null) stampSavedRinging(context)
            // Which alarm a Dismiss / Snooze was tapped on — the one up at this moment (see
            // answersAnotherRing). A Stop is not a tap: it ends whatever is up when it arrives.
            val tapped = _ringing.value?.takeIf { action != ACTION_STOP }?.let(::ringTag)
            _ringing.value = null
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
            runCatching {
                context.startService(
                    Intent(context, WearAlarmService::class.java).setAction(action)
                        // What this request ends is up now, not what is raised after it.
                        .putExtra(EXTRA_RAISED, fallbacksRaised.get())
                        .putExtra(EXTRA_TAPPED, tapped),
                )
            }.onFailure {
                // The service clears the saved copy of the alarm (stopRinging), after reading from
                // it whether this was a real ring. It won't run now, so drop the copy here: the
                // alarm has ended, and a later restart must not ring it again. With it goes the
                // note of a lifted Do Not Disturb filter, so that is given back here as well.
                takeSavedDndFilter(context)?.let { giveBackInterruptionFilter(context, it) }
                clearSavedRinging(context)
            }
        }

        /** Re-stamp the bypass-DND flag after the toggle / policy grant changes. */
        fun refreshChannel(context: Context) = ensureChannel(context)

        private fun overrideDnd(context: Context): Boolean =
            runCatching { WearApp.from(context).preferences.alarmOverrideDnd.value }.getOrDefault(false)

        private fun ensureChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Glucose alarms",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Out-of-range and signal-loss alarms"
                setSound(null, null) // the service loops its own audio
                enableVibration(false) // and drives its own vibration
                setBypassDnd(overrideDnd(context))
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /**
         * Background-safe fallback when the FGS start is blocked: heads-up with channel sound.
         * It must still lead to the crown-dismissible surface every way the user can react —
         * full-screen intent, a tap on the pop itself, opening the app (ringing state), or the
         * notification's own Dismiss/Snooze actions — because this path IS what the user gets
         * when everything else was blocked. No service runs here: endAlarm()'s direct clears
         * take care of the state, and a back-in-range value ends it via stop().
         */
        private fun postFallback(
            context: Context,
            title: String,
            text: String,
            timeoutSeconds: Int,
            test: Boolean,
            kind: WearAlarmController.Kind?,
        ) {
            _ringing.value = RingingAlarm(title, text, test = test, kind = kind)
            // Saved like a ring of the service: a Dismiss / Snooze reads from it whether this is a
            // real alarm (and which), and an instance that later keeps this alarm's surface
            // (ACTION_SURFACE) is sticky — its restart after a process death must find this
            // alarm, not a generic one or the texts of an earlier ring. That instance's
            // keepSurface also re-stamps the copy, which nothing else does without a ring.
            saveRinging(context, title, text, timeoutSeconds, test, kind)
            val channelId = "wear_glucose_alarm_fallback"
            val channel = NotificationChannel(channelId, "Glucose alarms (fallback)", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                setBypassDnd(overrideDnd(context))
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
            val fullScreen = PendingIntent.getActivity(
                context,
                1,
                alarmScreenIntent(context, title, text),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // A notification action's PendingIntent runs with a user-interaction exemption, so
            // the service start inside these succeeds even though the plain FGS start was blocked.
            fun actionIntent(action: String) = PendingIntent.getService(
                context,
                action.hashCode(),
                Intent(context, WearAlarmService::class.java).setAction(action).putExtra(EXTRA_BY_NOTIFICATION, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(de.mogluco.wear.R.drawable.ic_glucose)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(false)
                .setContentIntent(fullScreen)
                .setFullScreenIntent(fullScreen, true)
                .addAction(0, "Snooze", actionIntent(ACTION_SNOOZE))
                .addAction(0, "Dismiss", actionIntent(ACTION_DISMISS))
                .setDeleteIntent(actionIntent(ACTION_DISMISS))
                .build()
            runCatching { nm.notify(NOTIF_ID, n) }
        }
    }
}

/**
 * How long after an alarm was last known to be on its saved copy still stands for it. A restart
 * after a process death comes within seconds, a minute or two under memory pressure; a copy older
 * than this was left behind by an alarm that ended with its process (reboot, app update,
 * force-stop) and describes one that no longer exists.
 */
internal const val SAVED_RING_MAX_AGE_MS = 10 * 60_000L

/**
 * Whether the saved copy of an alarm can still be the one that a process death cut off: it was
 * stamped ([aliveAtMillis], re-stamped while ringing) no longer than [SAVED_RING_MAX_AGE_MS] ago.
 * A stamp that far in the future is as implausible (the clock was set), and a copy without a
 * stamp is one nobody can vouch for.
 */
internal fun savedRingFresh(aliveAtMillis: Long, now: Long): Boolean =
    aliveAtMillis > 0L && kotlin.math.abs(now - aliveAtMillis) <= SAVED_RING_MAX_AGE_MS

/**
 * How long after an alarm was last known to be on the Do Not Disturb filter it lifted is still
 * given back by whatever comes next (a restored ring, the next alarm, a late restart that only
 * drops the outdated copy). Longer than [SAVED_RING_MAX_AGE_MS]: the alarm itself must not come
 * back after ten minutes, but its lifted filter is still off then, and nothing else switches it
 * on again for the night — the bound covers a late restart and the next alarm of the excursion
 * (default repeat 15 min, snooze 30 min). Not without limit: the filter being off is all there
 * is to see, and after hours that is as likely the user's doing or the end of a schedule (the
 * morning after a Bedtime mode) as ours. Switching Do Not Disturb on then would be the worse
 * mistake of the two: a filter left off only lets more through, one switched on by mistake
 * silences the watch until somebody notices.
 */
internal const val LIFTED_DND_MAX_AGE_MS = 60 * 60_000L

/** Whether a lifted-filter record stamped at [aliveAtMillis] still counts as ours ([LIFTED_DND_MAX_AGE_MS]). */
internal fun liftedDndRecordValid(aliveAtMillis: Long, now: Long): Boolean =
    aliveAtMillis > 0L && kotlin.math.abs(now - aliveAtMillis) <= LIFTED_DND_MAX_AGE_MS
