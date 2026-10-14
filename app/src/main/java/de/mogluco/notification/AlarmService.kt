package de.mogluco.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import de.mogluco.MoglucoApp
import de.mogluco.data.VibrationPattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

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

    // Re-stamps the saved copy of the ring for as long as it lasts, so that its age says when the
    // ring was last known to be on (see savedRingFresh).
    private val keepSaved = object : Runnable {
        override fun run() {
            runCatching {
                getSharedPreferences(RINGING_PREFS, MODE_PRIVATE).edit()
                    .putLong(KEY_ALIVE_AT, System.currentTimeMillis()).apply()
            }
            handler.postDelayed(this, SAVED_RING_STAMP_MS)
        }
    }

    // Android cancels a non-system app's vibration when the screen turns off for most reasons other
    // than the plain timeout (power key, proximity, a gesture) — even with USAGE_ALARM — while the
    // looping sound keeps playing. Re-issue the waveform shortly after each screen-off while ringing,
    // under a short wakelock so the delayed post runs even if the device would otherwise suspend.
    private var ringPattern: VibrationPattern? = null
    private val rearmVibration = Runnable {
        val vib = vibrator
        val pattern = ringPattern
        if (vib != null && pattern != null) playWaveform(vib, pattern)
        runCatching { if (rearmWakeLock?.isHeld == true) rearmWakeLock?.release() }
    }
    private var screenOffReceiver: BroadcastReceiver? = null
    private val rearmWakeLock: PowerManager.WakeLock? by lazy {
        runCatching {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "juggluco:alarm-rearm")
                .apply { setReferenceCounted(false) }
        }.getOrNull()
    }

    private var title: String = "Glucose alarm"
    private var text: String = ""

    // The interruption filter in force before we lifted DND for this ring, or null when we
    // didn't touch it. Restored when the ring stops.
    private var previousInterruptionFilter: Int? = null

    // The alarm notification was posted as a plain one because startForeground was refused (see
    // onStartCommand); stopForeground won't remove that, so stopRinging cancels it.
    private var plainNotification = false

    // The id of the newest start this instance was handed — a Dismiss / Snooze / Stop counts as
    // one. The instance ends with it (see stopRinging), never with a plain stopSelf().
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // A Dismiss / Snooze tapped on the alarm before the one that rings now (its start was
        // already on its way when the tap came, and got here first) is not this one's answer:
        // the ring stays, and so does what its own start said about restarts.
        if (intent != null && answersAnotherRing(intent)) return START_STICKY
        // The three actions can land on an instance the system created just for them (nothing
        // was ringing, or the process had restarted). That one never rings and never goes
        // foreground: stopRinging finds nothing to end and leaves with this start's id.
        when (intent?.action) {
            // Only a real alarm that is still ringing feeds the excursion state: stopping a test
            // ring, or a Dismiss arriving after the ring already ended, must not mark the current
            // excursion as dismissed (nor silence the watch).
            ACTION_DISMISS -> {
                if (isRealRing()) AlarmController.onUserDismiss(this, realRingKind(this))
                stopRinging(byTimeout = false); return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                if (isRealRing()) AlarmController.onUserSnooze(this, realRingKind(this))
                stopRinging(byTimeout = false); return START_NOT_STICKY
            }
            // Silence without snoozing — used when the condition resolved on its own (e.g. signal
            // returned, or glucose back in range), so there's nothing to snooze.
            ACTION_STOP -> { stopRinging(byTimeout = false); return START_NOT_STICKY }
        }
        // A START_STICKY restart after process death arrives with a null intent: restore the alarm
        // that was ringing from the copy saved below, so it rings on with its own texts and
        // duration rather than a guessed one. 0 = until dismissed (also what a restart falls back
        // to when nothing was saved).
        val saved = if (intent == null) readSavedRing(this) else null
        if (saved != null && !saved.fresh) {
            // The ring this copy describes was last on too long ago to be the one a restart is
            // owed (see savedRingFresh): don't ring an old alarm with an old value. What the
            // glucose is now, AlarmController decides with the next reading or loss check.
            android.util.Log.w(TAG, "not restoring an outdated alarm")
            clearSavedRing(this)
            cancelLeftoverNotification()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        title = intent?.getStringExtra(EXTRA_TITLE) ?: saved?.title ?: title
        text = intent?.getStringExtra(EXTRA_TEXT) ?: saved?.text ?: text
        val timeoutSec = intent?.getIntExtra(EXTRA_TIMEOUT_SEC, 0) ?: saved?.timeoutSec ?: 0
        val test = intent?.getBooleanExtra(EXTRA_TEST, false) ?: saved?.test ?: false
        val kind = if (intent != null) AlarmController.kindOf(intent.getStringExtra(EXTRA_KIND)) else saved?.kind
        handler.removeCallbacks(keepSaved)
        if (intent != null) {
            runCatching {
                getSharedPreferences(RINGING_PREFS, MODE_PRIVATE).edit()
                    .putString(EXTRA_TITLE, title)
                    .putString(EXTRA_TEXT, text)
                    .putInt(EXTRA_TIMEOUT_SEC, timeoutSec)
                    .putBoolean(EXTRA_TEST, test)
                    .putString(EXTRA_KIND, kind?.name)
                    .putLong(KEY_ALIVE_AT, System.currentTimeMillis())
                    .apply()
            }
            handler.postDelayed(keepSaved, SAVED_RING_STAMP_MS)
        } else {
            keepSaved.run() // the restored ring is on again
        }
        // Publish the ringing state before any surface can observe it: the alarm screen and the
        // in-app banner follow it, and the alarm screen closes itself once it turns null.
        _ringing.value = RingingAlarm(title, text, restored = intent == null, test = test, kind = kind)
        // Only now that the ring shows there: no moment in which neither says so (see ringUpOrPending).
        if (intent != null) startsUnderway.updateAndGet { (it - 1).coerceAtLeast(0) }

        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        // The restart after process death is a start from the background, which Android 12+ may
        // refuse (ForegroundServiceStartNotAllowedException) unless the app is exempt from battery
        // optimisation. Uncaught that would crash the process instead of ringing — so ring anyway
        // and post the alarm notification (Dismiss / Snooze, full-screen intent) as a plain one.
        val notification = buildNotification()
        plainNotification = false
        runCatching { ServiceCompat.startForeground(this, NOTIF_ID, notification, type) }
            .onFailure {
                android.util.Log.w(TAG, "startForeground refused; ringing without it", it)
                plainNotification = true
                runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification) }
            }

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
            ringPattern = pattern
            playWaveform(vib, pattern)
            registerScreenOffRearm()
        }
    }

    /**
     * Start (or restart) the looping alarm waveform. repeatIndex 0 → loop the whole waveform until
     * cancelled. Alarm-usage attributes keep the vibration exempt from DND/ringer policies even when
     * the interruption-filter lift wasn't available (no policy access granted).
     */
    private fun playWaveform(vib: Vibrator, pattern: VibrationPattern) {
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
        }.onFailure { android.util.Log.w(TAG, "alarm vibration failed", it) }
    }

    private fun registerScreenOffRearm() {
        if (screenOffReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Let the platform's screen-off cancellation land first, then restart the pattern.
                runCatching { rearmWakeLock?.acquire(2_000L) }
                handler.removeCallbacks(rearmVibration)
                handler.postDelayed(rearmVibration, REARM_DELAY_MS)
            }
        }
        // ACTION_SCREEN_OFF is a protected system broadcast; NOT_EXPORTED still receives it.
        runCatching {
            ContextCompat.registerReceiver(
                this, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenOffReceiver = receiver
        }.onFailure { android.util.Log.w(TAG, "screen-off re-arm unavailable", it) }
    }

    private fun unregisterScreenOffRearm() {
        handler.removeCallbacks(rearmVibration)
        runCatching { if (rearmWakeLock?.isHeld == true) rearmWakeLock?.release() }
        screenOffReceiver?.let { r -> runCatching { unregisterReceiver(r) } }
        screenOffReceiver = null
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

    /**
     * Whether a Dismiss / Snooze refers to a real alarm: one is ringing and it is not a test. After
     * a process restart the saved copy stands in until the ring itself is restored — while it can
     * still be the ring that was cut off (see [savedRingFresh]).
     */
    private fun isRealRing(): Boolean {
        _ringing.value?.let { return !it.test }
        return readSavedRing(this)?.let { it.fresh && !it.test } == true
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
     */
    private fun stopRinging(byTimeout: Boolean) {
        val ended = _ringing.value
        // Nothing rings any more, so there is nothing for a restart to restore.
        _ringing.value = null
        handler.removeCallbacks(keepSaved)
        clearSavedRing(this)
        handler.removeCallbacks(autoStop)
        // stop() can throw (e.g. IllegalStateException on a half-prepared player) — release in
        // its own runCatching so a failed stop never leaks the MediaPlayer.
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
        unregisterScreenOffRearm()
        runCatching { vibrator?.cancel() }
        vibrator = null
        ringPattern = null
        restoreInterruptionFilter()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        // Not where the next ring is already on its way to this service (a repeat crossing the
        // timeout): that excursion has a ring, it has not rung out.
        if (byTimeout && ended?.test == false && startsUnderway.get() == 0) {
            AlarmController.onTimeout(this, ended.kind)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        plainNotification = false
        cancelLeftoverNotification()
        stopSelf(lastStartId)
    }

    /**
     * Take down the ring's notification where it is up as a plain one (startForeground was
     * refused, see onStartCommand) — this instance's, or one that outlived the instance that
     * posted it: the process was killed under it, and the note in [plainNotification] went with
     * it. Nothing else removes that one; it is ongoing, and its Dismiss / Snooze reach an
     * instance that knows nothing of it. Only the ring's own notification, by its channel: a
     * fallback alarm raised meanwhile has the same id, and must stay.
     */
    private fun cancelLeftoverNotification() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.activeNotifications.any { it.id == NOTIF_ID && it.notification.channelId == CHANNEL_ID }) {
                manager.cancel(NOTIF_ID)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // stopRinging clears the ringing state before it leaves the service, so a real alarm
        // still up here did not end through Dismiss, Snooze, Stop or its timeout. Nor is it an
        // alarm raised right behind one of those: stopRinging leaves the instance to a start
        // that is queued, and one issued later gets an instance of its own, after this one.
        val cutOff = _ringing.value?.takeIf { !it.test }
        _ringing.value = null
        // Destroyed in a live process, the ring is over however it came to that (the system
        // stops a service it refused the foreground after about a minute in the background):
        // no restart is owed, so nothing may be left that claims a ring in progress.
        handler.removeCallbacks(keepSaved)
        handler.removeCallbacks(autoStop)
        clearSavedRing(this)
        // Its excursion must not count as answered, though. Only now that nothing claims a
        // ring any more — the controller looks at exactly that.
        if (cutOff != null) {
            android.util.Log.w(TAG, "alarm cut off while ringing")
            AlarmController.onRingCutOff(cutOff.kind)
        }
        if (plainNotification) cancelLeftoverNotification()
        unregisterScreenOffRearm()
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

    /**
     * What a ringing alarm shows: its title ("Low glucose") and detail ("62 mg/dL").
     *
     * @param restored the ring is one a service restart brought back after process death.
     *   [AlarmController]'s excursion state may have died with the process, so it checks this to
     *   still silence the ring once the value is back in range.
     * @param test a "Test alarm" from settings: stopping it leaves the excursion state and the
     *   watch alone.
     * @param kind which alarm it is, or null where that is not known (a test, a restart with
     *   nothing saved). [AlarmController] takes the excursion from it when a Dismiss / Snooze
     *   lands on a restored ring whose excursion it did not restore.
     */
    data class RingingAlarm(
        val title: String,
        val text: String,
        val restored: Boolean,
        val test: Boolean,
        val kind: AlarmController.Kind? = null,
    )

    /** The copy of the ring in progress as saved for a restart; [fresh] see [savedRingFresh]. */
    private class SavedRing(
        val title: String,
        val text: String,
        val timeoutSec: Int,
        val test: Boolean,
        val kind: AlarmController.Kind?,
        val fresh: Boolean,
    )

    companion object {
        private const val TAG = "AlarmService"

        /** Delay after a screen-off before re-issuing the waveform (after the platform cancels it). */
        private const val REARM_DELAY_MS = 300L
        private const val CHANNEL_ID = "glucose_alarm_v1"
        private const val FALLBACK_CHANNEL_ID = "glucose_alarm_fallback"
        private const val NOTIF_ID = 0xA1A2
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TIMEOUT_SEC = "timeout"
        private const val EXTRA_TEST = "test"
        private const val EXTRA_KIND = "kind"
        const val ACTION_DISMISS = "de.mogluco.ALARM_DISMISS"
        const val ACTION_SNOOZE = "de.mogluco.ALARM_SNOOZE"
        const val ACTION_STOP = "de.mogluco.ALARM_STOP"

        /**
         * The ringing alarm's title/text/timeout/test flag/kind, kept only while it rings (restart
         * restore), with the time the ring was last known to be on ([KEY_ALIVE_AT]).
         */
        private const val RINGING_PREFS = "alarm_ringing"
        private const val KEY_ALIVE_AT = "alive_at"

        /** How often a ring in progress re-stamps its saved copy. */
        private const val SAVED_RING_STAMP_MS = 60_000L

        /** The saved copy, or null when nothing is saved. Check [SavedRing.fresh] before trusting it. */
        private fun readSavedRing(context: Context): SavedRing? {
            val prefs = runCatching { context.getSharedPreferences(RINGING_PREFS, MODE_PRIVATE) }.getOrNull()
                ?: return null
            val title = prefs.getString(EXTRA_TITLE, null) ?: return null
            return SavedRing(
                title = title,
                text = prefs.getString(EXTRA_TEXT, null).orEmpty(),
                timeoutSec = prefs.getInt(EXTRA_TIMEOUT_SEC, 0),
                test = prefs.getBoolean(EXTRA_TEST, false),
                kind = AlarmController.kindOf(prefs.getString(EXTRA_KIND, null)),
                fresh = savedRingFresh(prefs.getLong(KEY_ALIVE_AT, 0L), System.currentTimeMillis()),
            )
        }

        private fun clearSavedRing(context: Context) {
            runCatching { context.getSharedPreferences(RINGING_PREFS, MODE_PRIVATE).edit().clear().apply() }
        }

        /**
         * Which real (non-test) alarm a Dismiss / Snooze refers to: the one ringing, or — before
         * a restart has brought the ring back — the one saved. Null when there is none, or its
         * kind is not known.
         */
        fun realRingKind(context: Context): AlarmController.Kind? {
            _ringing.value?.let { return if (it.test) null else it.kind }
            return readSavedRing(context)?.takeIf { it.fresh && !it.test }?.kind
        }

        /**
         * A real alarm was ringing when the previous process ended, and this one has not brought
         * it back (yet): after a process death a restart of this service does that; after a
         * reboot, an app update or a force-stop nothing does.
         */
        fun savedRealRingPending(context: Context): Boolean =
            _ringing.value == null && readSavedRing(context)?.let { it.fresh && !it.test } == true

        // Alarms handed to the system by [start] that onStartCommand has not got to yet: the
        // main thread may be busy for a while (app start), and until then [ringing] is still null.
        private val startsUnderway = AtomicInteger(0)

        /**
         * Whether a [stop] has anything to end: a ring is up, one is on its way to the service,
         * or a real one is saved for a restart to bring back. Where none of that holds, the stop
         * would only make the system create the service to stop it again.
         */
        fun ringUpOrPending(context: Context): Boolean =
            // In this order: a start is counted until its ring shows in the ringing state.
            startsUnderway.get() > 0 || _ringing.value != null || savedRealRingPending(context)

        private val _ringing = MutableStateFlow<RingingAlarm?>(null)

        /**
         * Non-null exactly while an alarm is ringing. [AlarmActivity] closes itself when it turns
         * null (timeout, back in range, dismissed on the watch), and MainActivity shows the in-app
         * Dismiss / Snooze banner from it — the only stop control when notifications are blocked.
         */
        val ringing: StateFlow<RingingAlarm?> get() = _ringing

        /** Start (or refresh) the ringing alarm. Falls back to a plain heads-up if the FGS start is blocked. */
        fun start(
            context: Context,
            title: String,
            text: String,
            timeoutSeconds: Int,
            test: Boolean = false,
            kind: AlarmController.Kind? = null,
        ) {
            val intent = Intent(context, AlarmService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_TIMEOUT_SEC, timeoutSeconds)
                .putExtra(EXTRA_TEST, test)
                .putExtra(EXTRA_KIND, kind?.name)
            startsUnderway.incrementAndGet()
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    startsUnderway.updateAndGet { (it - 1).coerceAtLeast(0) }
                    postFallback(context, title, text)
                }
        }

        fun dismiss(context: Context) = answer(context, ACTION_DISMISS)

        fun snooze(context: Context) = answer(context, ACTION_SNOOZE)

        /** A Dismiss / Snooze from one of the app's own surfaces (alarm screen, banner, settings). */
        private fun answer(context: Context, action: String) {
            // Which alarm the tap was on — the one up at this moment (see answersAnotherRing).
            val tapped = _ringing.value?.let(::ringTag)
            runCatching {
                context.startService(
                    Intent(context, AlarmService::class.java).setAction(action).putExtra(EXTRA_TAPPED, tapped),
                )
            }
        }

        /** On a Dismiss / Snooze sent by [answer]: the [ringTag] of the alarm it was tapped on. */
        private const val EXTRA_TAPPED = "tapped"

        // What tells one alarm from the one raised after it: a test from a real alarm, and a
        // real one by its kind. A repeat of the same excursion is the same alarm in this sense.
        private fun ringTag(ring: RingingAlarm): String = "${ring.test}|${ring.kind?.name.orEmpty()}"

        /**
         * Whether [request] (a Dismiss / Snooze) was tapped on another alarm than the one that
         * rings now. A new alarm is handed to the system from the thread its reading arrives on;
         * a tap in the moment before the main thread gets to it is queued behind it, and would
         * otherwise answer the new alarm: a Low dismissed — here and on the watch — by a tap
         * that was meant for "Signal lost". The notification's own buttons carry no tag (they
         * belong to whatever it shows), and neither does a request from an older version.
         */
        private fun answersAnotherRing(request: Intent): Boolean {
            if (request.action != ACTION_DISMISS && request.action != ACTION_SNOOZE) return false
            val tapped = request.getStringExtra(EXTRA_TAPPED) ?: return false
            return _ringing.value?.let { ringTag(it) != tapped } == true
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

/**
 * How long after a ring was last known to be on its saved copy still stands for it. A restart
 * after a process death comes within seconds, a minute or two under memory pressure; a copy older
 * than this was left behind by a ring that ended with its process (reboot, app update, force-stop)
 * and describes an alarm that no longer exists.
 */
internal const val SAVED_RING_MAX_AGE_MS = 10 * 60_000L

/**
 * Whether the saved copy of a ring can still be the ring that a process death cut off: it was
 * stamped ([aliveAtMillis], re-stamped every minute while ringing) no longer than
 * [SAVED_RING_MAX_AGE_MS] ago. A stamp that far in the future is as implausible (the clock was
 * set), and a copy without a stamp is one nobody can vouch for.
 */
internal fun savedRingFresh(aliveAtMillis: Long, now: Long): Boolean =
    aliveAtMillis > 0L && kotlin.math.abs(now - aliveAtMillis) <= SAVED_RING_MAX_AGE_MS
