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
package de.mogluco.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import de.mogluco.data.GlucoseUnit
import de.mogluco.wear.MainActivity
import de.mogluco.wear.WearApp
import de.mogluco.wear.complication.ArrowComplicationService
import de.mogluco.wear.complication.NumberArrowComplicationService
import de.mogluco.wear.complication.NumberComplicationService
import de.mogluco.wear.tile.GlucoseTileService
import tk.glucodata.Natives
import tk.glucodata.Sensors

/**
 * The watch's keep-alive + fan-out service: holds the process for BLE/mirror data, keeps an
 * ongoing-activity chip (value + trend) on the watch face, refreshes [GlucoseSnapshot], asks
 * complications and the tile to update on each reading, and feeds the alarm controller.
 *
 * The legacy watch app was kept alive by its watch face service; the redesign has no watch
 * face (blocked on Pixel Watch 3 anyway), so this FGS plays that role.
 */
class WearGlucoseService : LifecycleService() {

    /** What the surfaces last rendered ("124 →" / "--"); gates redundant update pushes. */
    private var lastPushedKey: String? = null

    /** Elapsed realtime of the last reconnect kick; seeded at start so the first waits one interval. */
    private var lastWatchdogKickElapsed = android.os.SystemClock.elapsedRealtime()

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        val app = WearApp.from(this)
        val repo = app.glucoseRepository
        val prefs = app.preferences

        lifecycleScope.launch {
            combine(
                repo.latest, repo.history, prefs.unit, prefs.targetLow, prefs.targetHigh,
            ) { reading, history, unit, low, high ->
                GlucoseSnapshot.Snapshot(
                    reading = reading,
                    history = history,
                    unit = unit,
                    targetLow = low.toDouble(),
                    targetHigh = high.toDouble(),
                )
            }.collectLatest { snapshot ->
                // Alarm evaluation runs on the engine's reading callback (WearApp wires it into
                // the repository), a process-lifetime path independent of this service.
                //
                // What the surfaces render only changes with new data (the flows above) or when
                // the reading crosses the 5.5-min staleness boundary. So instead of a minute
                // ticker (~1440 CPU wakeups/day), push now, sleep exactly until the boundary, and
                // push once more; a new snapshot cancels the wait (collectLatest). The dedup gate
                // also protects the WearOS complication/tile update quota — burning it on
                // no-change pushes could throttle the update for a REAL new reading.
                while (true) {
                    GlucoseSnapshot.update(snapshot)
                    val displayKey = chipText(snapshot)
                    if (displayKey != lastPushedKey) {
                        lastPushedKey = displayKey
                        updateOngoing(snapshot)
                        requestComplicationUpdates(this@WearGlucoseService)
                        TileService.getUpdater(this@WearGlucoseService)
                            .requestUpdate(GlucoseTileService::class.java)
                    }
                    val untilStale = snapshot.reading?.let {
                        GlucoseSnapshot.STALE_MILLIS - it.ageMillis(System.currentTimeMillis())
                    } ?: 0L
                    if (untilStale > 0) {
                        delay(untilStale + 1_000) // +1 s so the re-check lands past the boundary
                        continue
                    }
                    // Stale (or nothing yet). When this watch owns the sensor's BLE link, nudge a
                    // reconnect now and every few minutes until data flows again (a new reading
                    // cancels this via collectLatest). Mirror-only watches stop here as before.
                    if (!ownsSensorLink()) break
                    // Spaced globally (re-emissions of the combine restart this loop) and never
                    // sooner than one interval after the service started, so boot/warm-up and
                    // history reloads don't trigger back-to-back reconnects. Expired sensors are
                    // left alone (nothing to reconnect to; avoids an endless rescan).
                    val now = android.os.SystemClock.elapsedRealtime()
                    val wait = lastWatchdogKickElapsed + WATCHDOG_INTERVAL_MS - now
                    if (wait > 0) {
                        delay(wait)
                        continue
                    }
                    lastWatchdogKickElapsed = now
                    if (repo.currentSensor?.isExpired() != true) runCatching { Sensors.watchdogKick() }
                    delay(WATCHDOG_INTERVAL_MS)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startAsForeground(GlucoseSnapshot.current)
        return START_STICKY
    }

    private fun startAsForeground(snapshot: GlucoseSnapshot.Snapshot?) {
        // The foreground service always needs a notification, but the user can slim it to a minimal
        // one (no value chip on the watch face) via the persistent-notification toggle.
        val showChip = de.mogluco.wear.WearApp.from(this).preferences.persistentNotification.value
        val builder = baseNotification(snapshot, full = showChip)
        if (showChip) attachOngoingActivity(builder, snapshot)
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            builder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    /** Refresh the notification + ongoing chip with the latest value. */
    private fun updateOngoing(snapshot: GlucoseSnapshot.Snapshot) {
        val status = Status.Builder()
            .addPart("value", Status.TextPart(chipText(snapshot)))
            .build()
        val recovered = OngoingActivity.recoverOngoingActivity(this)
        if (recovered != null) {
            recovered.update(this, status)
        } else {
            startAsForeground(snapshot)
        }
    }

    private fun ownsSensorLink(): Boolean =
        runCatching { Natives.getusebluetooth() }.getOrDefault(false)

    private fun chipText(snapshot: GlucoseSnapshot.Snapshot?): String {
        if (snapshot == null) return "--"
        val now = System.currentTimeMillis()
        return if (snapshot.isUsable(now)) {
            "${snapshot.valueText(now)} ${snapshot.trend(now).glyph}"
        } else {
            "--"
        }
    }

    private fun baseNotification(snapshot: GlucoseSnapshot.Snapshot?, full: Boolean): NotificationCompat.Builder {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // The ongoing chip returns to the app's Home menu, resetting the stack if needed.
                .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DEST_HOME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(de.mogluco.wear.R.drawable.ic_glucose)
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        if (full) {
            builder.setContentTitle(chipText(snapshot))
                .setContentText((snapshot?.unit ?: GlucoseUnit.MGDL).label)
        } else {
            builder.setContentTitle("Mogluco")
                .setContentText("Running in the background")
                .setPriority(NotificationCompat.PRIORITY_MIN)
        }
        return builder
    }

    private fun attachOngoingActivity(
        builder: NotificationCompat.Builder,
        snapshot: GlucoseSnapshot.Snapshot?,
    ) {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // The ongoing chip returns to the app's Home menu, resetting the stack if needed.
                .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DEST_HOME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        OngoingActivity.Builder(this, NOTIF_ID, builder)
            .setStaticIcon(de.mogluco.wear.R.drawable.ic_glucose)
            .setTouchIntent(tap)
            .setStatus(
                Status.Builder().addPart("value", Status.TextPart(chipText(snapshot))).build(),
            )
            .build()
            .apply(this)
    }

    companion object {
        private const val CHANNEL_ID = "wear_glucose_ongoing"

        /** Reconnect-kick spacing while this watch owns the sensor but readings are stale. */
        private const val WATCHDOG_INTERVAL_MS = 5 * 60_000L
        private const val NOTIF_ID = 0x91

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WearGlucoseService::class.java),
                )
            }
        }

        /**
         * Re-issue the ongoing notification so a persistent-notification-toggle change takes effect
         * immediately (otherwise it only applies on the next reading). Re-delivers to the already
         * running service — onStartCommand rebuilds the notification from the current preference.
         */
        fun refresh(context: Context) = start(context)

        /** Ask all three complication data sources to re-render from the snapshot. */
        fun requestComplicationUpdates(context: Context) {
            listOf(
                NumberComplicationService::class.java,
                ArrowComplicationService::class.java,
                NumberArrowComplicationService::class.java,
            ).forEach { cls ->
                ComplicationDataSourceUpdateRequester
                    .create(context, ComponentName(context, cls))
                    .requestUpdateAll()
            }
        }

        private fun ensureChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Glucose (ongoing)",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps glucose updating and shows the watch-face chip" }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
