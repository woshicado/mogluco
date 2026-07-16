package de.mogluco.wear

import android.app.Application
import android.content.Context
import de.mogluco.data.GlucoseRepository
import de.mogluco.data.MockGlucoseRepository
import de.mogluco.data.NativeGlucoseRepository
import de.mogluco.wear.alarm.WearAlarmController
import tk.glucodata.WatchLink

/**
 * Watch application entry point + tiny manual dependency container, mirroring the
 * phone's `MoglucoApp` pattern.
 */
class WearApp : Application() {

    val preferences: WearPreferences by lazy { WearPreferences(this) }

    lateinit var glucoseRepository: GlucoseRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // Sample data drives watch UI development (no sensor/phone on a lone emulator);
        // in native mode values arrive from the phone via the mirror, or from the sensor
        // over the watch's own BLE after a transfer.
        glucoseRepository = if (USE_SAMPLE_DATA) {
            MockGlucoseRepository(preferences)
        } else {
            NativeGlucoseRepository(
                this,
                preferences,
                // Evaluate the watch's own alarms on each LIVE reading, straight from the engine
                // callback — a process-lifetime path, so a dangerous low/high still rings even if
                // WearGlucoseService has been killed (parity with the phone's MoglucoApp seam).
                onReading = { mgdl, timeMillis ->
                    WearAlarmController.onReading(this, mgdl, timeMillis)
                },
            )
        }
        if (!USE_SAMPLE_DATA) {
            // Unit + target band follow the phone's engine settings (delivered over the
            // mirror): copy them out now and again whenever the engine state is replaced.
            preferences.syncFromEngine()
            tk.glucodata.Engine.addInvalidateListener { preferences.syncFromEngine() }
            // Start the signal-loss watchdog on the app (process) lifetime, not tied to the
            // foreground service — parity with the phone's AlarmController.init.
            WearAlarmController.ensureWatchdog(this)
            // Phone pushes its display prefs (unit + target band) over the mirror — authoritative
            // over the engine snapshot once received.
            WatchLink.setUiPrefsListener { unitMmol, low, high ->
                preferences.applyUiPrefs(unitMmol, low, high)
            }
            // Dismissing the alarm on the phone silences the watch's matching alarm.
            WatchLink.setAlarmDismissListener { WearAlarmController.onRemoteDismiss(this) }
            // On reconnect, fold in any readings the peer sends that we missed while disconnected,
            // and report how many were new so the "Re-sync" button can show a real outcome.
            WatchLink.setHistoryListener { points ->
                WatchLink.notifyCatchupMerged(glucoseRepository.mergeBackfill(points))
            }
            // Phone pushes the watch's alarm timings (repeat/snooze/timeout).
            WatchLink.setWearAlarmListener { r, s, t -> preferences.applyWearAlarm(r, s, t) }
            // Phone link: discovery, mirror handshake, /askforstart bootstrap loop.
            // Needs the booted engine (repository construction above started it).
            WatchLink.init(this)
        }
    }

    companion object {
        const val USE_SAMPLE_DATA = false

        fun from(context: Context): WearApp = context.applicationContext as WearApp
    }
}
