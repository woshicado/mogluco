package de.mogluco.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import de.mogluco.MoglucoApp
import de.mogluco.MainActivity
import de.mogluco.data.AppPreferences
import de.mogluco.data.GlucosePoint
import de.mogluco.data.buildGapLine
import de.mogluco.data.GlucoseReading
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.SensorInfo
import de.mogluco.data.windowed

/**
 * A foreground service that keeps an ongoing notification showing the current glucose, its
 * trend arrow, time since the reading, and a small graph of the last ~15 minutes. It reads
 * straight from the app's [de.mogluco.data.GlucoseRepository], so it works with the sample
 * data today and with live sensor data once that path is connected.
 *
 * The notification is lockscreen-visible (VISIBILITY_PUBLIC); whether it also renders on an
 * always-on display is up to the device's AOD settings, but an ongoing low-priority
 * notification is what AOD surfaces when enabled.
 *
 * This service is also the app's only background keep-alive: [AlarmController] and the BLE
 * connection live in this process. So it runs when the persistent notification *or* any glucose
 * alarm is enabled — with the notification pref off it shows a minimal "alarms active" face
 * instead of the readout (see [buildMinimal]) and only stops when both are off.
 */
class GlucoseNotificationService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var job: Job? = null

    // Cache the rendered bitmaps so the staleness-boundary re-post (which only flips the
    // "no signal" text) doesn't re-rasterize all three bitmaps on a service that runs 24/7. Keyed
    // by what the bitmaps actually depend on (reading value + history window), so a real new
    // reading still re-renders. Touched only from the single collect coroutine.
    private var bitmapKey: String? = null
    private var aodIcon: Bitmap? = null
    private var miniGraph: Bitmap? = null
    private var wideGraph: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        // Post an initial placeholder so startForeground is called promptly. On Android 14+
        // the foreground-service type must be passed explicitly.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        val prefs = (application as MoglucoApp).preferences
        val initial = if (prefs.persistentNotification.value) buildPlaceholder() else buildMinimal()
        androidx.core.app.ServiceCompat.startForeground(this, NOTIF_ID, initial, type)
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job == null) observe()
        return START_STICKY
    }

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
            ) { low, high, loss -> low || high || loss }
            combine(repo.latest, repo.history, repo.sensor, display, alarmsEnabled) {
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
                .collectLatest { s ->
                    if (!s.persistent && !s.alarmsEnabled) {
                        // Nothing left to keep alive. MainActivity normally stops us, but the
                        // pref can also flip while no activity is around.
                        androidx.core.app.ServiceCompat.stopForeground(
                            this@GlucoseNotificationService,
                            androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE,
                        )
                        stopSelf()
                        return@collectLatest
                    }
                    // A new reading lands as a latest+history+sensor burst; sit it out before
                    // rendering — collectLatest drops the intermediate snapshots while we wait.
                    delay(300)
                    val mgr = getSystemService(NotificationManager::class.java)
                    // The reading's age is rendered live by the system (setWhen + chronometer),
                    // so no minute ticker is needed to age a "N min ago" text. The one remaining
                    // time-driven change is the flip to the explicit "no signal" marker at the
                    // 10-min staleness boundary: sleep exactly until then and re-post once; a new
                    // snapshot cancels the wait (collectLatest). During prolonged signal loss the
                    // notification then stays put — the chronometer keeps counting on its own.
                    while (true) {
                        mgr.notify(NOTIF_ID, if (s.persistent) build(s) else buildMinimal())
                        // Only the full face has a staleness marker to age into.
                        val reading = if (s.persistent) s.reading else null
                        val untilStale = reading
                            ?.let { STALE_MILLIS - it.ageMillis(System.currentTimeMillis()) }
                            ?: break
                        if (untilStale <= 0) break
                        delay(untilStale + 1_000) // +1 s so the re-post lands past the boundary
                    }
                }
        }
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
        val title = if (reading != null) {
            "${s.unit.format(reading.calibratedMgdl)} ${s.unit.label} ${reading.trend.glyph}"
        } else {
            "Waiting for readings"
        }
        val text = buildString {
            // An old value must not keep looking fresh: past ~10 min without a reading,
            // say explicitly that this is the last known value, not a current one. The age
            // itself is the header chronometer (see setWhen below), not text we re-post.
            if (reading != null && reading.isStale(now, STALE_MILLIS)) append("no signal")
            s.sensor?.let {
                if (isNotEmpty()) append(" · ")
                append("day ${it.dayOfLife(now)}/${it.lifetimeDays}")
            }
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(if (s.lockscreen) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
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
        ensureBitmaps(s)
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
     * (Re)render the notification bitmaps only when what they depend on changed. The
     * staleness-boundary re-post only flips the "no signal" text; without this gate it would
     * also re-rasterize all three bitmaps on a service that runs around the clock.
     */
    private fun ensureBitmaps(s: Snapshot) {
        val reading = s.reading
        val key = if (reading == null) {
            "none"
        } else {
            "${reading.epochMillis}|${reading.calibratedMgdl}|${s.unit}|" +
                "${s.history.size}|${s.history.lastOrNull()?.epochMillis ?: 0L}"
        }
        if (key == bitmapKey) return
        bitmapKey = key
        aodIcon = reading?.let { renderAodIcon(s.unit.format(it.calibratedMgdl), s.history, s.gapMillis, s.targetLow, s.targetHigh) }
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
            this, 0, Intent(this, MainActivity::class.java),
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
     */
    private fun renderAodIcon(
        value: String,
        history: List<GlucosePoint>,
        gapMillis: Long,
        targetLow: Double,
        targetHigh: Double,
    ): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val white = AndroidColor.WHITE

        val now = System.currentTimeMillis()
        val start = now - 90 * 60_000L
        val recent = history.windowed(start, now)
        val hasGraph = recent.size >= 2

        // Value — fills the icon when there's no graph, otherwise the top ~58%.
        val valueBand = if (hasGraph) size * 0.58f else size.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            textSize = if (hasGraph) size * 0.46f else size * 0.7f
        }
        val maxWidth = size * 0.98f
        val tw = paint.measureText(value)
        if (tw > maxWidth) paint.textSize *= maxWidth / tw
        val fm = paint.fontMetrics
        c.drawText(value, size / 2f, valueBand / 2f - (fm.ascent + fm.descent) / 2f, paint)

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
        super.onDestroy()
    }

    companion object {
        // v2: recreated with PUBLIC lockscreen visibility so it shows on the lockscreen/AOD.
        private const val CHANNEL_ID = "glucose_ongoing_v2"
        private const val MINIMAL_CHANNEL_ID = "glucose_keepalive_v1"
        private const val NOTIF_ID = 1001

        /** The widest window any of the notification's renderers draw (the BigPicture graph). */
        private const val MAX_WINDOW_MILLIS = 3 * 60 * 60_000L

        /** Past this age a reading is presented as "no signal" rather than as a fresh value. */
        private const val STALE_MILLIS = 10 * 60_000L

        /**
         * Whether this service should be running at all. Alarms are evaluated in-process
         * ([AlarmController] sees readings only while BLE is connected), and this FGS is the
         * only thing keeping the process alive in the background — so the notification pref
         * alone must not decide it; any enabled glucose alarm needs it too.
         */
        fun shouldRun(prefs: AppPreferences): Boolean =
            prefs.persistentNotification.value || anyAlarmEnabled(prefs)

        /** Any alarm that requires a live process to fire (low/high/signal-loss). */
        fun anyAlarmEnabled(prefs: AppPreferences): Boolean =
            prefs.lowAlarmEnabled.value || prefs.highAlarmEnabled.value || prefs.signalLossAlarm.value

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
