package de.mogluco.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import de.mogluco.MoglucoApp
import de.mogluco.data.VibrationPattern

/**
 * Foreground service that *rings* an alarm: it loops the configured sound and vibration until
 * the user dismisses it (or a configurable timeout elapses), wakes the screen, and posts a
 * high-importance heads-up notification with Dismiss / Snooze actions. The notification also
 * carries a full-screen intent so it surfaces (and wakes the device) on a locked/off screen,
 * while appearing as a banner when the phone is already in use.
 *
 * It only handles the *ringing*; whether and when to ring (glucose thresholds, repeat, snooze)
 * is decided by [AlarmController].
 */
class AlarmService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopRinging(byTimeout = true) }

    private var title: String = "Glucose alarm"
    private var text: String = ""

    // The interruption filter in force before we lifted DND for this ring, or null when we
    // didn't touch it. Restored when the ring stops.
    private var previousInterruptionFilter: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> { AlarmController.onUserDismiss(this); stopRinging(byTimeout = false); return START_NOT_STICKY }
            ACTION_SNOOZE -> { AlarmController.onUserSnooze(this); stopRinging(byTimeout = false); return START_NOT_STICKY }
            // Silence without snoozing — used when the condition resolved on its own (e.g. signal
            // returned, or glucose back in range), so there's nothing to snooze.
            ACTION_STOP -> { stopRinging(byTimeout = false); return START_NOT_STICKY }
        }
        title = intent?.getStringExtra(EXTRA_TITLE) ?: title
        text = intent?.getStringExtra(EXTRA_TEXT) ?: text
        val timeoutSec = intent?.getIntExtra(EXTRA_TIMEOUT_SEC, 60) ?: 60

        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)

        startRinging()
        handler.removeCallbacks(autoStop)
        if (timeoutSec > 0) handler.postDelayed(autoStop, timeoutSec * 1000L)
        return START_STICKY
    }

    private fun startRinging() {
        val app = application as MoglucoApp
        val prefs = app.preferences

        // "Override Do Not Disturb". Two mechanisms, by platform:
        //  - API ≤ 34: lift the interruption filter for the duration of the ring (restored in
        //    stopRinging/onDestroy, and only if the user hasn't changed it meanwhile). DND with
        //    alarms suppressed (or Total Silence) mutes the alarm stream/vibration otherwise.
        //  - API 35+: setInterruptionFilter no longer changes user-activated DND (it only
        //    manages the app's own implicit zen rule — verified: the call "succeeds" but zen
        //    stays on), so don't call it. There the bypass-DND flag on our channels (stamped in
        //    refreshChannels while policy access is held) is what rings through: the system
        //    exempts apps with DND-bypassing channels from priority-DND audio/vibration muting
        //    and lets the heads-up through. Total Silence stays user-sovereign on 35+.
        // Both need the user-granted policy access, so warn when it's missing — this is a
        // safety feature, and swallowing that would make "why didn't my alarm ring
        // through DND" undebuggable.
        if (prefs.alarmOverrideDnd.value && previousInterruptionFilter == null) {
            runCatching {
                val nm = getSystemService(NotificationManager::class.java)
                when {
                    !nm.isNotificationPolicyAccessGranted ->
                        android.util.Log.w(TAG, "DND override on, but notification-policy access is not granted")
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> {} // channel bypass only
                    nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL -> {
                        previousInterruptionFilter = nm.currentInterruptionFilter
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                        android.util.Log.i(TAG, "Lifted DND for the alarm (was filter $previousInterruptionFilter)")
                    }
                }
            }.onFailure { android.util.Log.w(TAG, "DND override failed", it) }
        }

        // Briefly hold a screen wake-lock so the device lights up for the heads-up banner.
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "juggluco:alarm",
            ).apply { runCatching { acquire(10_000L) } }
        }

        if (prefs.alarmSound.value && player == null) {
            val uriStr = prefs.alarmSoundUri.value
            val custom: Uri? = if (uriStr.isNotEmpty()) Uri.parse(uriStr) else null
            val default: Uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            // The custom sound can fail long after it was picked (URI permission grant revoked,
            // file deleted). A soundless LOW alarm is not acceptable, so fall back to the system
            // default alarm sound and only stay silent when that fails too.
            val primary = custom ?: default
            player = createLoopingPlayer(primary)
                ?: if (primary != default) createLoopingPlayer(default) else null
        }

        if (prefs.alarmVibrate.value && vibrator == null) {
            val vib = currentVibrator()
            vibrator = vib
            val pattern: VibrationPattern = prefs.vibrationPattern.value
            // repeatIndex 0 → loop the whole waveform until cancelled. Alarm-usage attributes
            // keep the vibration exempt from DND/ringer policies even when the interruption-
            // filter lift above wasn't available (no policy access granted).
            runCatching {
                val effect = VibrationEffect.createWaveform(pattern.timings, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(
                        effect,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                }
            }
        }
    }

    /**
     * Undo the DND lift from [startRinging] — but only when the filter is still the ALL we set;
     * if the user changed DND while the alarm rang, their choice wins.
     */
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

    /** A prepared, started, looping alarm player for [uri] — or null (player released) on failure. */
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
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrElse {
            // Release the half-initialised player, or every failed attempt leaks one.
            runCatching { mp.release() }
            null
        }
    }

    /** Stop the sound/vibration and leave the service. */
    private fun stopRinging(byTimeout: Boolean) {
        handler.removeCallbacks(autoStop)
        // stop() can throw (e.g. IllegalStateException on a half-prepared player) — release in
        // its own runCatching so a failed stop never leaks the MediaPlayer.
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        restoreInterruptionFilter()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        if (byTimeout) AlarmController.onTimeout(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { player?.release() }
        runCatching { vibrator?.cancel() }
        restoreInterruptionFilter()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    }

    private fun currentVibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VibratorManager::class.java)).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }

    private fun buildNotification(): Notification {
        fun actionIntent(action: String) = PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, AlarmService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Full-screen intent: shown as a heads-up banner when the screen is on, and launched
        // (waking the device) on a locked/off screen.
        val fullScreen = PendingIntent.getActivity(
            this,
            1,
            Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, "Snooze", actionIntent(ACTION_SNOOZE))
            .addAction(0, "Dismiss", actionIntent(ACTION_DISMISS))
            // Android 14 lets users swipe away even FGS notifications: without this the alarm
            // would keep ringing with its only stop control gone. Treat a swipe as Dismiss.
            .setDeleteIntent(actionIntent(ACTION_DISMISS))
            .build()
    }

    companion object {
        private const val TAG = "AlarmService"
        private const val CHANNEL_ID = "glucose_alarm_v1"
        private const val FALLBACK_CHANNEL_ID = "glucose_alarm_fallback"
        private const val NOTIF_ID = 0xA1A2
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TIMEOUT_SEC = "timeout"
        const val ACTION_DISMISS = "de.mogluco.ALARM_DISMISS"
        const val ACTION_SNOOZE = "de.mogluco.ALARM_SNOOZE"
        const val ACTION_STOP = "de.mogluco.ALARM_STOP"

        /** Start (or refresh) the ringing alarm. Falls back to a plain heads-up if the FGS start is blocked. */
        fun start(context: Context, title: String, text: String, timeoutSeconds: Int) {
            val intent = Intent(context, AlarmService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_TIMEOUT_SEC, timeoutSeconds)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, intent) }
                .onFailure { postFallback(context, title, text) }
        }

        fun dismiss(context: Context) {
            runCatching {
                context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_DISMISS))
            }
        }

        /** Silence a ringing alarm without snoozing (the alarm condition resolved on its own). */
        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_STOP))
            }
        }

        /** Background-safe fallback: a high-importance heads-up notification (no looping audio). */
        private fun postFallback(context: Context, title: String, text: String) {
            ensureFallbackChannel(context)
            val n = NotificationCompat.Builder(context, FALLBACK_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
        }

        /**
         * (Re)issue both alarm channels. Called on every alarm and from Settings when the
         * "Override Do Not Disturb" toggle or its access grant changes: re-issuing
         * createNotificationChannel for an existing id is an idempotent update, and it's the
         * only way the bypass-DND flag ever takes effect — the system honors an app-set
         * bypassDnd only while the app holds notification-policy access (and the user hasn't
         * customized the channel), so the flag must be re-stamped after the grant.
         */
        fun refreshChannels(context: Context) {
            ensureChannel(context)
            ensureFallbackChannel(context)
        }

        private fun overrideDnd(context: Context): Boolean =
            (context.applicationContext as? MoglucoApp)?.preferences?.alarmOverrideDnd?.value == true

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(NotificationManager::class.java)
            // High importance for heads-up; we manage sound/vibration ourselves, so the channel
            // itself is silent (no double sound). No exists-check: re-creating updates the
            // bypass-DND flag to track the override toggle (see refreshChannels).
            val channel = NotificationChannel(CHANNEL_ID, "Glucose alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Out-of-range and signal-loss alarms"
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(overrideDnd(context))
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            mgr.createNotificationChannel(channel)
        }

        private fun ensureFallbackChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(NotificationManager::class.java)
            // Unlike the main channel (silent — the service loops its own audio), the fallback
            // fires precisely when the ringing service could NOT start, so nothing else will make
            // noise: let the channel itself sound the system alarm tone and vibrate. Without this
            // a missed FGS start would degrade a LOW alarm to a silent banner. Bypass-DND matters
            // most here — no service runs in this path to lift the interruption filter.
            val channel = NotificationChannel(
                FALLBACK_CHANNEL_ID, "Glucose alarms (fallback)", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alarm used when the full ringing alarm can't start in the background"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                setBypassDnd(overrideDnd(context))
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            mgr.createNotificationChannel(channel)
        }
    }
}
