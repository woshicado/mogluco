package de.mogluco.notification

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import de.mogluco.MoglucoApp
import de.mogluco.MainActivity
import de.mogluco.data.AppPreferences
import de.mogluco.data.GlucosePoint
import de.mogluco.data.buildGapLine
import de.mogluco.data.GlucoseReading
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.SensorInfo
import de.mogluco.data.windowed
import de.mogluco.ui.theme.DarkGlucoseColors

/**
 * A foreground service that keeps an ongoing notification showing the current glucose, its
 * trend arrow, time since the reading, and a small graph of the last ~15 minutes. It reads
 * straight from the app's [de.mogluco.data.GlucoseRepository], so it works with the sample
 * data today and with live sensor data once that path is connected.
 *
 * The notification is lockscreen-visible (VISIBILITY_PUBLIC) unless "Show on lockscreen" is off
 * (see [readoutVisibility]); whether it also renders on an always-on display is up to the
 * device's AOD settings, but an ongoing low-priority notification is what AOD surfaces when
 * enabled.
 *
 * This service is also the app's only background keep-alive: [AlarmController] and the BLE
 * connection live in this process. So it runs when the persistent notification *or* any glucose
 * alarm is enabled — with the notification pref off it shows a minimal "alarms active" face
 * instead of the readout (see [buildMinimal]) and only stops when both are off.
 */
class GlucoseNotificationService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var job: Job? = null

    // Cache the rendered bitmaps so a re-post that changes none of them doesn't re-rasterize all
    // three on a service that runs 24/7. Keyed by what the bitmaps actually depend on (reading
    // value + history window + whether the reading is stale), so a real new reading and the
    // staleness boundary still re-render. Touched only from the single collect coroutine.
    private var bitmapKey: String? = null
    private var aodIcon: Bitmap? = null
    private var miniGraph: Bitmap? = null
    private var wideGraph: Bitmap? = null

    // Whether this instance is in the foreground. startForeground can be refused (see
    // goForeground); every later start tries again, and the readout is only posted once it holds.
    private val foreground = MutableStateFlow(false)
    private var warnedNotForeground = false

    // Bumped to have the collector post the readout again (see goForeground).
    private val repost = MutableStateFlow(0)

    // The staleness boundary of the shown reading, as an AlarmManager wakeup (see armStaleTick).
    private val staleTick = MutableStateFlow(0)
    @Volatile private var staleTickArmed = false
    private val staleTickIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_STALE_TICK).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE,
        )
    }
    private val staleTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // AlarmManager's wake lock ends when this returns, but the re-post happens on the
            // collect coroutine: bridge the moment until it has run.
            runCatching {
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "juggluco:stale-tick")
                    .acquire(3_000L)
            }
            staleTick.update { it + 1 }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        runCatching {
            ContextCompat.registerReceiver(
                this, staleTickReceiver, IntentFilter(ACTION_STALE_TICK), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { android.util.Log.w(TAG, "stale tick unavailable", it) }
        goForeground()
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Not only onCreate: a start can reach an instance whose startForeground was refused. If
        // it came through startForegroundService (the app was opened, as the warning asks), the
        // system kills the app unless this instance goes foreground now.
        goForeground()
        if (job == null) observe()
        return START_STICKY
    }

    /**
     * Enter the foreground if this instance is not there yet, with a placeholder so that
     * startForeground is called promptly; the collector posts the real face right after.
     *
     * A START_STICKY restart after process death is a start from the background, which Android
     * 12+ may refuse (ForegroundServiceStartNotAllowedException) unless the app is exempt from
     * battery optimisation. Uncaught that crashes the process — and with it BLE and the alarms —
     * again on every restart attempt. Keep running as long as the system lets us, and say that
     * monitoring needs the app opened (which starts this service properly, and then takes the
     * warning down again).
     */
    private fun goForeground() {
        // On Android 14+ the foreground-service type must be passed explicitly.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        val prefs = (application as MoglucoApp).preferences
        // Also where this instance already counts itself in the foreground — then with what is up
        // now, so the readout does not fall back to the placeholder. Every start through
        // startForegroundService has to be answered by startForeground, whatever came before: the
        // system can ignore the call without saying so (for an app whose background use the user
        // has restricted it "succeeds", and the service is not in the foreground), and a start
        // that found this instance taking itself for one went unanswered — the system killed the
        // app half a minute later.
        val initial = (if (foreground.value) shownNotification() else null)
            ?: if (prefs.persistentNotification.value) buildPlaceholder() else buildMinimal()
        val wasForeground = foreground.value
        val result = runCatching { androidx.core.app.ServiceCompat.startForeground(this, NOTIF_ID, initial, type) }
        // Ignored without a word — background use "Restricted", started while the app is not on
        // screen: the call returns, and the service is not in the foreground. Taken for success,
        // the readout would be posted as a plain notification that nothing takes down when the
        // service is stopped, its value frozen. From Android 14 on, where the type is passed, the
        // service then has none. (Before, it cannot be told; there the call is taken at its word.)
        val ignored = result.isSuccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            foregroundServiceType == 0 // no type: not in the foreground
        if (result.isSuccess && !ignored) {
            foreground.value = true
            BootReceiver.cancelOpenAppNotification(this)
            // Answered with what was up a moment ago: the collector's latest post may have been
            // under way meanwhile, and would then lose to this older one. Have it post again,
            // after this.
            if (wasForeground) repost.update { it + 1 }
        } else {
            android.util.Log.w(TAG, "startForeground refused; running without it", result.exceptionOrNull())
            foreground.value = false
            // Once per refusal: the restart's onCreate and onStartCommand both get here.
            if (!warnedNotForeground) {
                warnedNotForeground = true
                BootReceiver.postOpenAppNotification(this)
            }
        }
    }

    /** The readout as it is posted now, if it is. */
    private fun shownNotification(): android.app.Notification? = runCatching {
        getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == NOTIF_ID }?.notification
    }.getOrNull()

    private fun observe() {
        val app = application as MoglucoApp
        val repo = app.glucoseRepository
        val prefs = app.preferences
        job = scope.launch {
            val display = combine(
                prefs.unit, prefs.showOnLockscreen, prefs.persistentNotification,
            ) { unit, lockscreen, persistent -> Triple(unit, lockscreen, persistent) }
            val alarmsEnabled = combine(
                prefs.lowAlarmEnabled, prefs.highAlarmEnabled, prefs.signalLossAlarm,
                prefs.signalLossNotification,
            ) { low, high, loss, lossNotification -> low || high || loss || lossNotification }
            val snapshots = combine(repo.latest, repo.history, repo.sensor, display, alarmsEnabled) {
                reading, history, sensor, (unit, lockscreen, persistent), alarms ->
                // Slice the recent window once, by binary search: the full history can be
                // ~300k points and the renderers only ever look at the last 3 h.
                val now = System.currentTimeMillis()
                Snapshot(
                    reading, history.windowed(now - MAX_WINDOW_MILLIS, now), sensor,
                    unit, lockscreen, persistent, alarms,
                    gapMillis = prefs.gapThresholdMinutes.value * 60_000L,
                    targetLow = prefs.targetLow.value.toDouble(),
                    targetHigh = prefs.targetHigh.value.toDouble(),
                )
            }
            combine(snapshots, foreground, repost) { s, inForeground, _ -> s to inForeground }
                .collectLatest { (s, inForeground) ->
                    if (!s.persistent && !s.alarmsEnabled) {
                        // Nothing left to keep alive. MainActivity normally stops us, but the
                        // pref can also flip while no activity is around.
                        //
                        // Not on this snapshot's word alone, though: a backup restore reloads
                        // the settings one by one, and "everything off" can be a moment on the
                        // way from "only the notification" to "only an alarm". MainActivity sees
                        // on → on then and starts nothing again, so a stop here would leave the
                        // process without its keep-alive. Sit the moment out (collectLatest
                        // drops this snapshot when the next one lands) and stop only if the
                        // settings still say so — on the main thread, where MainActivity's
                        // start is issued too, so the two cannot cross.
                        delay(SETTLE_MILLIS)
                        withContext(Dispatchers.Main) {
                            if (shouldRun(prefs)) return@withContext // a newer snapshot follows
                            cancelStaleTick()
                            androidx.core.app.ServiceCompat.stopForeground(
                                this@GlucoseNotificationService,
                                androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE,
                            )
                            // Should a start still reach this instance, it has to enter the
                            // foreground again (see goForeground).
                            foreground.value = false
                            stopSelf()
                        }
                        return@collectLatest
                    }
                    // Outside the foreground (startForeground refused) nothing is posted: a plain
                    // notification is not taken down with the service or the process, and would
                    // then show its last value for good. goForeground flips this on success.
                    if (!inForeground) {
                        cancelStaleTick()
                        return@collectLatest
                    }
                    // A new reading lands as a latest+history+sensor burst; sit it out before
                    // rendering — collectLatest drops the intermediate snapshots while we wait.
                    delay(SETTLE_MILLIS)
                    val mgr = getSystemService(NotificationManager::class.java)
                    // The reading's age is rendered live by the system (setWhen + chronometer),
                    // so no minute ticker is needed to age a "N min ago" text. The one remaining
                    // time-driven change is the flip to the stale face ("no signal", greyed icon)
                    // at the 10-min staleness boundary: wait exactly until then and re-post once;
                    // a new snapshot cancels the wait (collectLatest). During prolonged signal
                    // loss the notification then stays put — the chronometer keeps counting on
                    // its own.
                    while (true) {
                        mgr.notify(NOTIF_ID, if (s.persistent) build(s) else buildMinimal())
                        // Only the full face has a stale variant to age into.
                        val untilStale = (if (s.persistent) s.reading else null)
                            ?.let { millisUntilStale(it.epochMillis, System.currentTimeMillis(), STALE_MILLIS) }
                            ?: 0L
                        if (untilStale <= 0) {
                            cancelStaleTick()
                            break
                        }
                        // The timeout below stands still while the CPU is suspended — and once
                        // readings stop, nothing else wakes an idle phone: the old value would
                        // pass for a current one for hours. So the boundary is also an
                        // AlarmManager wakeup, moved with every reading; whichever comes first.
                        val seen = staleTick.value
                        armStaleTick(untilStale)
                        withTimeoutOrNull(untilStale) { staleTick.first { it != seen } }
                    }
                }
        }
    }

    /**
     * Arm the one-shot wakeup for the staleness boundary, [delayMillis] from now, replacing the
     * previous one (same PendingIntent). With a live sensor it is moved before it ever fires.
     *
     * In Doze only an allow-while-idle alarm fires on time, but the system rations those per app
     * (one per ~9 minutes) unless the app is exempt from battery optimisation — and the
     * signal-loss check ([SignalLossReceiver]) is due a few minutes after this one. A cosmetic
     * tick must not push an alarm back, so without the exemption this is an ordinary alarm: on
     * time whenever the phone merely sleeps, held until a maintenance window in deep Doze.
     */
    private fun armStaleTick(delayMillis: Long) {
        runCatching {
            val alarms = getSystemService(AlarmManager::class.java)
            val at = SystemClock.elapsedRealtime() + delayMillis
            if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, staleTickIntent)
            } else {
                alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, staleTickIntent)
            }
            staleTickArmed = true
        }.onFailure { android.util.Log.w(TAG, "could not arm the stale tick", it) }
    }

    private fun cancelStaleTick() {
        if (!staleTickArmed) return
        staleTickArmed = false
        runCatching { getSystemService(AlarmManager::class.java).cancel(staleTickIntent) }
    }

    private data class Snapshot(
        val reading: GlucoseReading?,
        val history: List<GlucosePoint>,
        val sensor: SensorInfo?,
        val unit: GlucoseUnit,
        val lockscreen: Boolean,
        val persistent: Boolean,
        val alarmsEnabled: Boolean,
        // The no-data gap threshold (0 = never break). Read from prefs at snapshot time so the
        // notification/AOD graphs break across sensor gaps like the in-app chart, instead of
        // drawing a straight line across a long stretch with no readings.
        val gapMillis: Long,
        // Target band (mg/dL), read from prefs at snapshot time (like gapMillis). The graphs anchor
        // their y-scale on this band and draw it faintly, so a jump's size is readable instead of the
        // axis rescaling to whatever the recent window holds.
        val targetLow: Double,
        val targetHigh: Double,
    )

    private fun build(s: Snapshot): Notification {
        val now = System.currentTimeMillis()
        val reading = s.reading
        // An old value must not keep looking fresh: past ~10 min without a reading, every part
        // that the status bar, the always-on display or the shade shows says so — the icon is
        // greyed and loses its sparkline (see renderAodIcon), the title loses its trend glyph (a
        // direction that no longer holds), and the text says "no signal". The age itself is the
        // header chronometer (see setWhen below), not text we re-post.
        val stale = reading != null && reading.isStale(now, STALE_MILLIS)
        val title = if (reading != null) {
            readoutTitle(s.unit.format(reading.calibratedMgdl), s.unit.label, reading.trend.glyph, stale)
        } else {
            "Waiting for readings"
        }
        val text = buildString {
            if (stale) append("no signal")
            s.sensor?.let {
                if (isNotEmpty()) append(" · ")
                append("day ${it.dayOfLife(now)}/${it.lifetimeDays}")
            }
        }
        val tap = PendingIntent.getActivity(
            this, 0, MainActivity.resumeIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(readoutVisibility(s.lockscreen))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        // The header shows how old the reading is as a live count-up anchored on the reading's
        // own timestamp — the system re-renders it, so the service never re-posts just to age
        // an "N min ago" text (that used to be a wakeup every 60 s around the clock).
        if (reading != null) {
            builder.setShowWhen(true)
                .setWhen(reading.epochMillis)
                .setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }

        // Always-on-display / status-bar icon: the only surface stock AOD renders for a
        // notification is its (monochrome) small icon, so we pack the whole glanceable widget —
        // the value on top and a sparkline of the recent trend below — into it. xDrip+ uses the
        // same "draw into the small icon" trick because AOD won't show the body or large icon.
        ensureBitmaps(s, stale)
        val icon = aodIcon
        if (reading != null && icon != null) {
            builder.setSmallIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(icon))
        } else {
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
        }

        // Lockscreen (when you raise the phone): a thumbnail graph when collapsed, and a full
        // colour graph when the notification is expanded. Neither shows on AOD itself.
        miniGraph?.let { builder.setLargeIcon(it) }
        wideGraph?.let { big ->
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(big)
                    .bigLargeIcon(null as Bitmap?),
            )
        }
        return builder.build()
    }

    /**
     * (Re)render the notification bitmaps only when what they depend on changed: a new reading
     * or history window, or the reading turning [stale] (the icon has a stale variant, and the
     * graphs are redrawn up to the present, with the gap since the last reading). Without this
     * gate every re-post would re-rasterize all three bitmaps on a service that runs around the
     * clock.
     */
    private fun ensureBitmaps(s: Snapshot, stale: Boolean) {
        val reading = s.reading
        val key = if (reading == null) {
            "none"
        } else {
            "${reading.epochMillis}|${reading.calibratedMgdl}|${s.unit}|" +
                "${s.history.size}|${s.history.lastOrNull()?.epochMillis ?: 0L}|$stale"
        }
        if (key == bitmapKey) return
        bitmapKey = key
        aodIcon = reading?.let {
            renderAodIcon(s.unit.format(it.calibratedMgdl), s.history, s.gapMillis, s.targetLow, s.targetHigh, stale)
        }
        miniGraph = renderMiniGraph(s)
        wideGraph = renderWideGraph(s)
    }

    private fun buildPlaceholder(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Mogluco")
            .setContentText("Starting…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /**
     * The minimal keep-alive face: the user turned the persistent notification off but still has
     * glucose alarms enabled, so the process (BLE + [AlarmController]) must stay alive — yet they
     * asked for no glucose readout. No value, no graph, silent MIN-importance channel, and secret
     * on the lockscreen so nothing leaks where the full notification was opted out.
     */
    private fun buildMinimal(): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, MainActivity.resumeIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, MINIMAL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Glucose alarms active")
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * The AOD/status-bar widget: a square white-alpha bitmap with the current value on top and a
     * sparkline of the last ~90 minutes below. Drawn in white alpha so the system tints it for the
     * status bar and always-on display. With too little history it falls back to the value alone.
     *
     * A [stale] reading (no signal) gets its own face, so that the one surface that is always in
     * view never passes an old value off as the current one: the value greyed like the app's
     * stale reading (as alpha — the system tints the icon, so grey is all a colour can say here),
     * and a broken flat line where the sparkline with its "now" dot would be.
     */
    private fun renderAodIcon(
        value: String,
        history: List<GlucosePoint>,
        gapMillis: Long,
        targetLow: Double,
        targetHigh: Double,
        stale: Boolean,
    ): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val white = AndroidColor.WHITE

        val now = System.currentTimeMillis()
        val start = now - 90 * 60_000L
        val recent = history.windowed(start, now)
        val hasGraph = !stale && recent.size >= 2
        val lowerBand = hasGraph || stale

        // Value — fills the icon when there's nothing below it, otherwise the top ~58%.
        val valueBand = if (lowerBand) size * 0.58f else size.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            // The stale grey of the dark palette (what the always-on overlay greys its value
            // with), as the share of white it is.
            if (stale) alpha = (DarkGlucoseColors.stale.green * 255).toInt()
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            textSize = if (lowerBand) size * 0.46f else size * 0.7f
        }
        val maxWidth = size * 0.98f
        val tw = paint.measureText(value)
        if (tw > maxWidth) paint.textSize *= maxWidth / tw
        val fm = paint.fontMetrics
        c.drawText(value, size / 2f, valueBand / 2f - (fm.ascent + fm.descent) / 2f, paint)

        if (stale) {
            // "– – –": no line to draw. At full strength — in a 24 dp glyph this is the part
            // that says "no signal"; the greyed value alone would be too subtle.
            val y = size * 0.79f
            val dash = size * 0.2f
            val pitch = size * 0.3f
            val dashes = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 9f; strokeCap = Paint.Cap.ROUND; color = white
            }
            for (i in 0 until 3) {
                val x = size * 0.1f + i * pitch
                c.drawLine(x, y, x + dash, y, dashes)
            }
        }

        if (hasGraph) {
            val top = size * 0.62f
            val bottom = size * 0.96f
            val left = size * 0.05f
            val right = size * 0.95f
            // Anchor the scale on the target band (soft min/max) so a jump's size reads truthfully,
            // rather than a fully-dynamic axis. No band is drawn here — it's a monochrome status-bar
            // glyph — but the domain always spans the target range, padded a touch.
            val hasBand = targetLow > 0.0 && targetHigh > targetLow
            val dataMin = recent.minOf { it.calibratedMgdl }
            val dataMax = recent.maxOf { it.calibratedMgdl }
            val minV: Double
            val maxV: Double
            if (hasBand) {
                val margin = ((targetHigh - targetLow) * 0.15).coerceAtLeast(10.0)
                minV = minOf(dataMin, targetLow - margin)
                maxV = maxOf(dataMax, targetHigh + margin)
            } else {
                minV = dataMin
                maxV = dataMax
            }
            val span = (maxV - minV).coerceAtLeast(1.0)
            fun gx(t: Long) = left + ((t - start).toFloat() / (now - start)) * (right - left)
            fun gy(v: Double) = bottom - (((v - minV) / span).toFloat()) * (bottom - top)
            val path = Path()
            buildGapLine(recent, gapMillis, { gx(it.epochMillis) }, { gy(it.calibratedMgdl) }, { px, py -> path.moveTo(px, py) }, { px, py -> path.lineTo(px, py) })
            c.drawPath(
                path,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 9f
                    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = white
                },
            )
            val last = recent.last()
            c.drawCircle(
                gx(last.epochMillis), gy(last.calibratedMgdl), 11f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white },
            )
        }
        return bmp
    }

    /** A wider colour graph (last ~3 h) for the expanded lockscreen notification (BigPicture). */
    private fun renderWideGraph(s: Snapshot): Bitmap? =
        TrendGraphRenderer.lineGraph(
            s.history, System.currentTimeMillis(), windowMillis = 3 * 60 * 60_000L,
            widthPx = 1024, heightPx = 384, padPx = 24f, strokePx = 7f, dotPx = 9f, gridStrokePx = 2f,
            targetLow = s.targetLow, targetHigh = s.targetHigh,
            gapMillis = s.gapMillis,
        )

    /** Draws the last ~30 minutes of the calibrated trace into a small bitmap. */
    private fun renderMiniGraph(s: Snapshot): Bitmap? =
        TrendGraphRenderer.lineGraph(
            s.history, System.currentTimeMillis(), windowMillis = 30 * 60_000L,
            widthPx = 192, heightPx = 96, padPx = 8f, strokePx = 5f, dotPx = 6f,
            targetLow = s.targetLow, targetHigh = s.targetHigh,
            gapMillis = s.gapMillis,
        )

    override fun onDestroy() {
        scope.cancel()
        cancelStaleTick()
        runCatching { unregisterReceiver(staleTickReceiver) }
        // The system takes the readout down with a foreground service; one posted where it was
        // not (see goForeground) would otherwise stay, its value frozen.
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GlucoseNotifService"

        // v2: recreated with PUBLIC lockscreen visibility so it shows on the lockscreen/AOD.
        private const val CHANNEL_ID = "glucose_ongoing_v2"
        private const val MINIMAL_CHANNEL_ID = "glucose_keepalive_v1"
        private const val NOTIF_ID = 1001
        private const val ACTION_STALE_TICK = "de.mogluco.notification.STALE_TICK"

        /** The widest window any of the notification's renderers draw (the BigPicture graph). */
        private const val MAX_WINDOW_MILLIS = 3 * 60 * 60_000L

        /** Past this age a reading is presented as "no signal" rather than as a fresh value. */
        private const val STALE_MILLIS = 10 * 60_000L

        /** How long a snapshot has to stand before it is acted on: bursts settle within it. */
        private const val SETTLE_MILLIS = 300L

        /**
         * Whether this service should be running at all. Alarms are evaluated in-process
         * ([AlarmController] sees readings only while BLE is connected), and this FGS is the
         * only thing keeping the process alive in the background — so the notification pref
         * alone must not decide it; any enabled glucose alarm needs it too.
         */
        fun shouldRun(prefs: AppPreferences): Boolean =
            prefs.persistentNotification.value || anyAlarmEnabled(prefs)

        /**
         * Any alarm that requires a live process to fire (low/high/signal-loss) — the quiet
         * signal-loss notification included: it comes from the same in-process watchdog.
         */
        fun anyAlarmEnabled(prefs: AppPreferences): Boolean =
            prefs.lowAlarmEnabled.value || prefs.highAlarmEnabled.value || prefs.signalLossAlarm.value ||
                prefs.signalLossNotification.value

        fun start(context: Context) {
            val intent = Intent(context, GlucoseNotificationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GlucoseNotificationService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Glucose reading", NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Ongoing current glucose reading"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                mgr.createNotificationChannel(channel)
            }
            if (mgr.getNotificationChannel(MINIMAL_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    MINIMAL_CHANNEL_ID, "Alarm keep-alive", NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = "Minimal notification that keeps glucose alarms running"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }
}

/**
 * How long until a reading taken at [readingMillis] counts as stale, plus a second so that what is
 * rendered then lands past the boundary; 0 once it is stale.
 */
internal fun millisUntilStale(readingMillis: Long, now: Long, staleMillis: Long): Long {
    val age = (now - readingMillis).coerceAtLeast(0)
    return if (age > staleMillis) 0L else staleMillis - age + 1_000L
}

/**
 * The readout notification's lock-screen visibility for the "Show on lockscreen" switch. Off is
 * SECRET, not PRIVATE: a private notification is shown in full when the system is set to show all
 * notification content, and its redacted form still shows the small icon — which here is the
 * rendered glucose value.
 */
internal fun readoutVisibility(showOnLockscreen: Boolean): Int =
    if (showOnLockscreen) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_SECRET

/**
 * The readout notification's title. A [stale] value keeps its unit but loses the trend glyph: the
 * direction belongs to the moment of the reading, not to now.
 */
internal fun readoutTitle(value: String, unitLabel: String, trendGlyph: String, stale: Boolean): String =
    if (stale) "$value $unitLabel" else "$value $unitLabel $trendGlyph"
