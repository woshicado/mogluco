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
    private val autoStop = Runnable { stopRinging(byTimeout = true) }
    // The direct launch can lose a race against a screen-state transition (waking from ambient)
    // even when it's permitted; retry while the alarm still rings and no surface came up.
    private val relaunch = Runnable {
        if (_ringing.value != null && !WearAlarmActivity.visible) launchAlarmScreen()
    }

    // While ringing with the surface gone (backgrounded without a dismiss), keep trying to bring it
    // back on a slow cadence; the screen-on receiver below covers "user raised the wrist".
    private val keepSurface = object : Runnable {
        override fun run() {
            if (_ringing.value == null) return
            if (!WearAlarmActivity.visible) launchAlarmScreen()
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
    private var previousInterruptionFilter: Int? = null
    // "Ring through DND" is off AND a DND/zen mode is active → this alarm respects DND by staying
    // SILENT (no sound, no vibration) but still shows the full-screen surface so it's visible.
    private var dndSuppressed: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                WearAlarmController.onUserDismiss(); stopRinging(byTimeout = false); return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                WearAlarmController.onUserSnooze(this); stopRinging(byTimeout = false); return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopRinging(byTimeout = false); return START_NOT_STICKY
            }
            ACTION_SURFACE -> {
                // The surface went to the background while ringing — bring it back soon, then
                // keep retrying slowly. Not a new alarm: nothing else changes.
                if (_ringing.value != null) {
                    handler.removeCallbacks(keepSurface)
                    handler.postDelayed(keepSurface, 1_000)
                }
                return START_STICKY
            }
        }
        title = intent?.getStringExtra(EXTRA_TITLE) ?: title
        text = intent?.getStringExtra(EXTRA_TEXT) ?: text
        // 0 = ring until dismissed (the default). A restart after process death (START_STICKY,
        // null intent) also rings until dismissed — an alarm that was ringing must not go quiet.
        val timeoutSec = intent?.getIntExtra(EXTRA_TIMEOUT_SEC, 0) ?: 0
        // Publish the ringing state BEFORE any surface can observe it: MainActivity mirrors it to
        // the full-screen activity whenever the app is opened, and the activity closes on null.
        _ringing.value = RingingAlarm(title, text)

        // Decide DND handling up front. "Ring through DND" OFF + a DND mode active → show the
        // alarm silently (no sound/vibration) but still display the full-screen surface. Our
        // sound/vibration ride the alarm stream (USAGE_ALARM), which the default "Priority" DND
        // passes on its own, so without this gate the toggle did nothing. ON → force through
        // (startRinging lifts the interruption filter).
        dndSuppressed = computeDndSuppressed()

        ensureChannel(this)
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
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
                        previousInterruptionFilter = nm.currentInterruptionFilter
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    }
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
        runCatching { startActivity(alarmScreenIntent(this, title, text)) }
            .onFailure { android.util.Log.w(TAG, "direct alarm-screen launch failed", it) }
    }

    private fun restoreInterruptionFilter() {
        val previous = previousInterruptionFilter ?: return
        previousInterruptionFilter = null
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.isNotificationPolicyAccessGranted &&
                nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
            ) {
                nm.setInterruptionFilter(previous)
            }
        }
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

    private fun stopRinging(byTimeout: Boolean) {
        _ringing.value = null
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
        restoreInterruptionFilter()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Also clear a notification posted OUTSIDE foreground mode (the postFallback path, when
        // the FGS start itself was blocked) — stopForeground doesn't remove that one.
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        _ringing.value = null
        unregisterScreenOffRearm()
        runCatching { player?.release() }
        runCatching { vibrator?.cancel() }
        restoreInterruptionFilter()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    }

    private fun buildNotification(): Notification {
        fun actionIntent(action: String) = PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, WearAlarmService::class.java).setAction(action),
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

    /** What a ringing alarm shows: its title ("Low glucose") and detail ("3.4 mmol/L"). */
    data class RingingAlarm(val title: String, val text: String)

    companion object {
        private const val TAG = "WearAlarmService"
        private const val CHANNEL_ID = "wear_glucose_alarm"

        // Non-null exactly while the alarm is ringing. MainActivity observes it to bring up the
        // full-screen surface whenever the app is opened mid-alarm (any entry point: launcher,
        // complication, tile, ongoing chip); WearAlarmActivity observes it to close itself when
        // the alarm ends (dismiss from the phone, back-in-range, timeout).
        private val _ringing = kotlinx.coroutines.flow.MutableStateFlow<RingingAlarm?>(null)
        val ringing: kotlinx.coroutines.flow.StateFlow<RingingAlarm?> get() = _ringing
        private const val NOTIF_ID = 0xA1
        val VIBRATION_PATTERN = longArrayOf(0, 400, 250, 400, 250, 700)
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TIMEOUT_SEC = "timeout"
        const val ACTION_DISMISS = "de.mogluco.wear.ALARM_DISMISS"
        const val ACTION_SNOOZE = "de.mogluco.wear.ALARM_SNOOZE"
        const val ACTION_STOP = "de.mogluco.wear.ALARM_STOP"
        private const val ACTION_SURFACE = "de.mogluco.wear.ALARM_SURFACE"

        /** Slow retry cadence for bringing a backgrounded alarm surface back while ringing. */
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

        fun start(context: Context, title: String, text: String, timeoutSeconds: Int) {
            val intent = Intent(context, WearAlarmService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_TIMEOUT_SEC, timeoutSeconds)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { postFallback(context, title, text) }
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
        private fun endAlarm(context: Context, action: String) {
            _ringing.value = null
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
            runCatching {
                context.startService(Intent(context, WearAlarmService::class.java).setAction(action))
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
        private fun postFallback(context: Context, title: String, text: String) {
            _ringing.value = RingingAlarm(title, text)
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
                Intent(context, WearAlarmService::class.java).setAction(action),
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
