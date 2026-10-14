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
package de.mogluco.aod

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import de.mogluco.MoglucoApp
import de.mogluco.data.GlucosePoint
import de.mogluco.data.GlucoseReading
import de.mogluco.data.GlucoseState
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.glucoseState
import de.mogluco.notification.TrendGraphRenderer
import de.mogluco.ui.home.TrendArrowShape
import de.mogluco.ui.theme.DarkGlucoseColors
import de.mogluco.ui.theme.DarkOnSurfaceVariant
import de.mogluco.ui.theme.colorFor
import de.mogluco.ui.util.nextLocalMidnight
import de.mogluco.ui.util.startOfDay

/**
 * Opt-in "true always-on display" overlay.
 *
 * This is the same technique xDrip+ and JugglucoNG use: an [AccessibilityService] hosts a
 * [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] window, which can be drawn over the lock
 * screen and the ambient/always-on layer without the "draw over other apps" (`SYSTEM_ALERT_WINDOW`)
 * permission. The window shows the current value, trend arrow and a small colour graph while the
 * screen is off (the ambient / always-on-display state). Whether it *also* shows on the interactive
 * lock screen follows the "Show on lockscreen" preference (the same toggle that governs the
 * persistent notification): on → shown on the lock screen too and removed on unlock; off → limited
 * to the true screen-off state.
 *
 * This service is **purely additive** and read-only with respect to the rest of the app: it
 * subscribes to the same [de.mogluco.data.GlucoseRepository] flows the UI/notification already
 * use and never touches the sensor connection path. The persistent notification keeps working
 * exactly as before; this overlay is a separate feature the user can enable independently.
 *
 * Android never auto-prompts for an accessibility service — it only runs once the user enables
 * it in system settings — so simply shipping this code prompts nobody. The in-app toggle
 * (`AppPreferences.alwaysOnDisplay`) gates behaviour: if it is off when the service connects (or
 * is turned off while running) the overlay is removed and the service calls [disableSelf] so the
 * permission is released rather than left dangling.
 */
class AodOverlayService : AccessibilityService(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var receiverRegistered = false

    private var overlayView: View? = null
    private var valueText: TextView? = null
    private var ageText: TextView? = null
    private var arrowImage: ImageView? = null
    private var graphImage: ImageView? = null
    private var params: WindowManager.LayoutParams? = null

    private var isScreenOn = true
    private var isLocked = false
    // Mirrors the "Show on lockscreen" pref: when true the overlay may also appear on the interactive
    // lock screen (screen on + locked); when false it is limited to the true screen-off / AOD state.
    @Volatile private var showOnLock = false
    private var luxAlpha = 1.0f

    // OLED burn-in protection: the overlay can sit at the same pixels for hours while locked,
    // so a slow random walk nudges the window a few dp around the user's anchor (which stays
    // the walk's centre). Offsets in px, clamped to ±4dp.
    private var jitterJob: Job? = null
    private var jitterX = 0
    private var jitterY = 0

    // The alarm that re-renders the overlay when no new reading does (see [scheduleAgeTick]).
    private var ageTick: PendingIntent? = null

    // Latest data, cached so the overlay can be (re)drawn on screen-on without waiting for a new emission.
    @Volatile private var snapshot: Snapshot? = null

    private data class Snapshot(
        val reading: GlucoseReading?,
        val history: List<GlucosePoint>,
        val unit: GlucoseUnit,
        val targetRange: ClosedFloatingPointRange<Double>,
        // No-data gap threshold (0 = never break), so the overlay graph breaks across sensor gaps
        // like the in-app chart instead of drawing a straight line across a long no-data stretch.
        val gapMillis: Long,
    )

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> { isScreenOn = false; updateVisibility() }
                Intent.ACTION_SCREEN_ON -> { isScreenOn = true; isLocked = resolveLocked(); updateVisibility() }
                Intent.ACTION_USER_PRESENT -> { isLocked = false; updateVisibility() }
                ACTION_AGE_TICK -> if (overlayView?.visibility == View.VISIBLE) updateContent()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val prefs = (application as MoglucoApp).preferences
        // If the feature was switched off in-app, don't run: release the permission.
        if (!prefs.alwaysOnDisplay.value) {
            disableSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_AGE_TICK)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true

        val repo = (application as MoglucoApp).glucoseRepository
        // Live content: mirror the same repository flows the notification/UI read.
        scope.launch {
            combine(repo.latest, repo.history, repo.unit, repo.targetRange, prefs.gapThresholdMinutes) { reading, history, unit, range, gapMin ->
                Snapshot(reading, history, unit, range, gapMin * 60_000L)
            }.collect { s ->
                snapshot = s
                if (overlayView?.visibility == View.VISIBLE) updateContent()
            }
        }
        // Re-anchor live when the user changes the position preference while the overlay is up.
        scope.launch {
            prefs.aodPositionPercent.collect { if (overlayView?.visibility == View.VISIBLE) applyPosition() }
        }
        // Gate: turning the feature off in-app removes the overlay and releases the permission.
        scope.launch {
            prefs.alwaysOnDisplay.collect { enabled ->
                if (!enabled) {
                    hideOverlay()
                    disableSelf()
                }
            }
        }
        // "Show on lockscreen" decides whether the overlay may appear on the interactive lock screen
        // too, or only in the true screen-off / AOD state. Re-evaluate the moment it's toggled.
        scope.launch {
            prefs.showOnLockscreen.collect { on ->
                showOnLock = on
                updateVisibility()
            }
        }

        isLocked = resolveLocked()
        showOnLock = prefs.showOnLockscreen.value
        updateVisibility()
    }

    private fun resolveLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked || km.isDeviceLocked
    }

    private fun updateVisibility() {
        // Always show in the true screen-off / AOD state. On the interactive lock screen (screen on
        // + locked) show only when "Show on lockscreen" is enabled; once unlocked, never.
        if (!isScreenOn || (showOnLock && isLocked)) showOverlay() else hideOverlay()
    }

    @Suppress("DEPRECATION")
    private fun showOverlay() {
        if (overlayView == null) {
            buildOverlayView()
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT,
            )
            applyPosition()
            val added = runCatching { windowManager?.addView(overlayView, params) }.isSuccess
            if (!added) {
                // addView can throw on a transient invalid window token. Drop the detached view so
                // the next show rebuilds and retries, instead of toggling visibility on a view that
                // was never attached (which would leave the overlay permanently invisible).
                clearOverlayRefs()
                return
            }
        }
        overlayView?.visibility = View.VISIBLE
        updateContent()
        startJitter()
        lightSensor?.let {
            // Batch up to ~1s to limit wakeups while dozing.
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 1_000_000)
        }
    }

    private fun hideOverlay() {
        jitterJob?.cancel()
        jitterJob = null
        cancelAgeTick()
        sensorManager?.unregisterListener(this)
        val view = overlayView ?: return
        runCatching { windowManager?.removeView(view) }
        clearOverlayRefs()
    }

    /** Every 1–2 minutes take one random-walk step and re-apply the layout at the new offset. */
    private fun startJitter() {
        if (jitterJob?.isActive == true) return
        jitterJob = scope.launch {
            while (isActive) {
                delay(60_000L + Random.nextLong(60_000L))
                val step = dp(2)
                val bound = dp(4)
                jitterX = (jitterX + Random.nextInt(-step, step + 1)).coerceIn(-bound, bound)
                jitterY = (jitterY + Random.nextInt(-step, step + 1)).coerceIn(-bound, bound)
                applyPosition()
            }
        }
    }

    private fun clearOverlayRefs() {
        overlayView = null
        valueText = null
        ageText = null
        arrowImage = null
        graphImage = null
        params = null
    }

    /**
     * Anchor the overlay at the user's chosen vertical position: a percentage of screen height
     * (0 = top, 100 = bottom) that places the overlay's centre on that line, clamped on-screen.
     * The overlay height is only known once it has laid out, so we read its current measured height
     * and re-apply after layout (see [updateContent]).
     */
    private fun applyPosition() {
        val p = params ?: return
        val percent = (application as MoglucoApp).preferences.aodPositionPercent.value.coerceIn(0f, 100f)
        val screenH = resources.displayMetrics.heightPixels
        val viewH = overlayView?.height ?: 0
        val centerY = percent / 100f * screenH
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // The burn-in jitter offsets ride on top of the anchor (x offsets from the horizontal
        // centre), so the user's chosen position stays the visual centre of the walk.
        p.x = jitterX
        p.y = ((centerY - viewH / 2f).toInt() + jitterY)
            .coerceIn(0, (screenH - viewH).coerceAtLeast(0))
        overlayView?.let { v -> runCatching { windowManager?.updateViewLayout(v, p) } }
    }

    private fun buildOverlayView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.TRANSPARENT)
        }
        // The value and the trend arrow sit side-by-side, matching the in-app reading header.
        val valueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        }
        val value = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
        }
        val arrow = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(10) }
        }
        valueRow.addView(value)
        valueRow.addView(arrow)
        // Shown only under a stale (greyed) value: when that value was taken.
        val age = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(DarkOnSurfaceVariant.toArgb())
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
            visibility = View.GONE
        }
        val graph = ImageView(this).apply {
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(130)).apply {
                topMargin = dp(12)
            }
        }
        root.addView(valueRow)
        root.addView(age)
        root.addView(graph)
        overlayView = root
        valueText = value
        ageText = age
        arrowImage = arrow
        graphImage = graph
    }

    private fun updateContent() {
        val s = snapshot ?: return
        val tv = valueText ?: return
        val reading = s.reading
        val now = System.currentTimeMillis()
        if (reading != null) {
            // An old value must not keep looking current — after a disconnect the overlay can sit
            // there all night. Past the staleness threshold it is shown as Home shows it: greyed,
            // without the trend arrow (a direction that no longer holds), and with when it was taken.
            val stale = reading.isStale(now, STALE_MILLIS)
            val state = glucoseState(reading.calibratedMgdl, s.targetRange.start, s.targetRange.endInclusive)
            val color = if (stale) DarkGlucoseColors.stale.toArgb() else stateColor(state)
            tv.text = s.unit.format(reading.calibratedMgdl)
            tv.setTextColor(color)
            // Same hand-drawn, rate-tilted arrow as the in-app reading (see ui/home/TrendArrow.kt),
            // rather than a static unicode glyph, so the AOD matches the app.
            arrowImage?.let { iv ->
                if (stale) {
                    iv.visibility = View.GONE
                } else {
                    iv.setImageBitmap(arrowBitmap(reading.trendAngleDegrees, color, dp(48)))
                    iv.visibility = View.VISIBLE
                }
            }
            ageText?.let { at ->
                at.text = if (stale) takenAtLabel(reading.epochMillis, now) else null
                at.visibility = if (stale) View.VISIBLE else View.GONE
            }
        } else {
            tv.text = "--"
            tv.setTextColor(Color.WHITE)
            arrowImage?.visibility = View.GONE
            ageText?.visibility = View.GONE
        }
        scheduleAgeTick(reading, now)
        graphImage?.let { img ->
            val w = (resources.displayMetrics.widthPixels * 0.9f).toInt().coerceAtLeast(1)
            val bmp = TrendGraphRenderer.lineGraph(
                s.history, now, windowMillis = 3 * 60 * 60_000L,
                widthPx = w, heightPx = dp(130), padPx = dp(6).toFloat(),
                strokePx = dp(2).toFloat(), dotPx = dp(3).toFloat(), gridStrokePx = 1.5f,
                targetLow = s.targetRange.start, targetHigh = s.targetRange.endInclusive,
                gapMillis = s.gapMillis,
            )
            if (bmp != null) {
                img.setImageBitmap(bmp)
                img.visibility = View.VISIBLE
            } else {
                img.visibility = View.GONE
            }
        }
        applyAlpha()
        // Content (value + arrow) just changed the overlay's height; re-anchor once it re-lays out
        // so the percentage position stays centered.
        overlayView?.post { applyPosition() }
    }

    /**
     * (Re)arms the one alarm that re-renders the overlay when no new reading does: at the moment
     * the shown reading turns stale, and for a stale one at the next local midnight, when its
     * label gains the date (see [takenAtLabel]). Nothing in between — the label is a clock time,
     * which stays right without re-rendering. Every render re-arms it, so with a live sensor it
     * never fires.
     *
     * An inexact allow-while-idle alarm, not a coroutine delay: on the always-on display the CPU
     * is asleep most of the time, a delay only counts awake time (it would fire hours late), and
     * Doze defers ordinary alarms to its maintenance windows.
     */
    private fun scheduleAgeTick(reading: GlucoseReading?, now: Long) {
        if (reading == null) {
            cancelAgeTick()
            return
        }
        val alarms = getSystemService(AlarmManager::class.java) ?: return
        val tick = ageTick ?: PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_AGE_TICK).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE,
        ).also { ageTick = it }
        val age = reading.ageMillis(now)
        // +1 s so the render lands past the boundary.
        val delayMillis = if (age <= STALE_MILLIS) {
            STALE_MILLIS - age + 1_000
        } else {
            nextLocalMidnight(startOfDay(now)) - now + 1_000
        }
        // Setting it again replaces the pending one (same PendingIntent). Allow-while-idle only
        // with the battery exemption, as the readout notification's stale tick does: without it
        // an app gets about one such alarm per nine minutes in deep Doze, and this cosmetic one
        // must not take the slot the signal-loss check needs.
        val at = SystemClock.elapsedRealtime() + delayMillis
        val exempt = runCatching {
            getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)
        if (exempt) {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, tick)
        } else {
            alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, tick)
        }
    }

    private fun cancelAgeTick() {
        ageTick?.let { getSystemService(AlarmManager::class.java)?.cancel(it) }
    }

    /**
     * When a stale reading was taken, as a clock time in the user's locale and 12/24-hour setting:
     * "at 02:00", or with the date ("10 Oct, 02:00") once it is no longer today's. A relative "N min
     * ago" was only as current as its last render, and the overlay re-renders rarely (and late,
     * under Doze): it read "6 min ago" for the ten minutes after the reading turned stale.
     */
    private fun takenAtLabel(epochMillis: Long, now: Long): String {
        if (startOfDay(epochMillis) == startOfDay(now)) {
            return "at " + DateUtils.formatDateTime(this, epochMillis, DateUtils.FORMAT_SHOW_TIME)
        }
        return DateUtils.formatDateTime(
            this,
            epochMillis,
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }

    /**
     * The glucose trend arrow drawn to a bitmap, matching the in-app [de.mogluco.ui.home.TrendArrow]
     * (a short chunky shaft with a wide rounded head), rotated by the reading's rate-derived angle.
     */
    private fun arrowBitmap(angleDeg: Float, colorInt: Int, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val s = sizePx.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            style = Paint.Style.STROKE
            strokeWidth = s * TrendArrowShape.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        canvas.save()
        canvas.rotate(angleDeg, s / 2f, s / 2f)
        TrendArrowShape.strokes(s) { x1, y1, x2, y2 -> canvas.drawLine(x1, y1, x2, y2, paint) }
        canvas.restore()
        return bmp
    }

    // The always-on overlay draws on black, so it uses the shared DARK glucose palette — the same
    // accessibility-tuned tokens (a low is magenta, not the red/green pair that fails many users)
    // as the rest of the app, so a change to the shared palette reaches the AOD instead of drifting.
    private fun stateColor(state: GlucoseState): Int = DarkGlucoseColors.colorFor(state).toArgb()

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_LIGHT) return
        val lux = event.values[0].coerceIn(1f, 200f)
        val logMin = Math.log(1.0); val logMax = Math.log(200.0)
        val norm = ((Math.log(lux.toDouble()) - logMin) / (logMax - logMin)).toFloat()
        val target = 0.4f + norm * 0.6f
        val smoothed = luxAlpha * 0.85f + target * 0.15f
        if (kotlin.math.abs(smoothed - luxAlpha) > 0.02f) {
            luxAlpha = smoothed
            applyAlpha()
        }
    }

    private fun applyAlpha() {
        overlayView?.alpha = luxAlpha
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Only matters when the overlay may show on the lock screen. While the screen is on, re-check
        // the authoritative keyguard state so the overlay hides as soon as the user is actually
        // unlocked (covers swipe-to-unlock with no secure lock, where USER_PRESENT may not fire).
        if (isScreenOn && showOnLock) {
            val locked = resolveLocked()
            if (locked != isLocked) {
                isLocked = locked
                updateVisibility()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        cancelAgeTick()
        sensorManager?.unregisterListener(this)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        clearOverlayRefs()
        scope.cancel()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /**
         * Past this age the overlay stops presenting a reading as current. The same threshold as
         * [GlucoseReading.isStale]'s default, which Home greys its reading at.
         */
        private const val STALE_MILLIS = 6 * 60_000L

        /** Private broadcast the age alarm delivers to this service's (non-exported) receiver. */
        private const val ACTION_AGE_TICK = "de.mogluco.aod.AGE_TICK"

        /** Whether the overlay accessibility service is currently enabled in system settings. */
        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
            val enabled = am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
            )
            val me = context.packageName
            return enabled.any {
                it.resolveInfo?.serviceInfo?.let { si ->
                    si.packageName == me && si.name == AodOverlayService::class.java.name
                } == true
            }
        }

        /** Deep-link the user to the system Accessibility settings to enable/disable the service. */
        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}
