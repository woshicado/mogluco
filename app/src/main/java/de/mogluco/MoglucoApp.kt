package de.mogluco

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import de.mogluco.data.AppPreferences
import de.mogluco.data.EventRepository
import de.mogluco.data.GlucoseRepository
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.MockEventRepository
import de.mogluco.data.MockGlucoseRepository
import de.mogluco.data.NativeEventRepository
import de.mogluco.data.NativeGlucoseRepository
import de.mogluco.data.NativeSettingsRepository
import de.mogluco.data.SettingsRepository
import de.mogluco.nfc.NfcController
import de.mogluco.onboarding.Libre3SensorActivator
import de.mogluco.onboarding.SensorActivator
import de.mogluco.onboarding.SimulatedSensorActivator

/** One emission of the phone's display prefs to mirror to the watch (see [MoglucoApp]). */
private data class UiPrefsPush(val hasWatch: Boolean, val mmol: Boolean, val low: Float, val high: Float)

/**
 * Application entry point and the app's tiny manual dependency container.
 */
class MoglucoApp : Application() {

    val preferences: AppPreferences by lazy { AppPreferences(this) }

    lateinit var glucoseRepository: GlucoseRepository
        private set

    lateinit var eventRepository: EventRepository
        private set

    val settingsRepository: SettingsRepository by lazy { NativeSettingsRepository() }

    val nfcController = NfcController()

    /**
     * Sample mode → [SimulatedSensorActivator] (walk the onboarding UX without hardware).
     * Native mode → [Libre3SensorActivator] (real NFC handshake + BLE, needs a physical sensor).
     * The account ID is read fresh from preferences at activation time.
     */
    val sensorActivator: SensorActivator by lazy {
        if (USE_SAMPLE_DATA) {
            SimulatedSensorActivator()
        } else {
            Libre3SensorActivator(accountIdProvider = { preferences.accountId.value.toLongOrNull() ?: 0L })
        }
    }

    override fun onCreate() {
        super.onCreate()
        // During the UI redesign we drive the screens from synthetic sample data so the
        // layout can be built and visually verified without a sensor (none on the emulator).
        // Flip USE_SAMPLE_DATA to false to run against the live native engine + a real Libre 3.
        if (USE_SAMPLE_DATA) {
            glucoseRepository = MockGlucoseRepository(preferences)
            eventRepository = MockEventRepository()
        } else {
            glucoseRepository = NativeGlucoseRepository(
                this,
                preferences,
                // Evaluate the phone's glucose alarms on each live reading (the shared repository
                // is app-agnostic; the watch passes its own controller here). The reading time is
                // unused on the phone — AlarmController stamps its own watchdog marker.
                onReading = { mgdl, _ -> de.mogluco.notification.AlarmController.onReading(this, mgdl) },
            )
            // Adopt the engine's configured unit + target band once, so a mmol/L user's phone
            // doesn't sit on the mg/dL 70–180 defaults until they reconfigure by hand (the engine
            // was started by the repository construction above).
            preferences.seedDisplayFromEngineOnce()
            eventRepository = NativeEventRepository()
            // Phone↔watch link: discovery, mirror handshakes, transfer state. Needs the
            // booted engine (the repository construction above started it).
            tk.glucodata.WatchLink.init(this)
            tk.glucodata.WatchLink.startAutoReclaimMonitor(
                enabled = preferences.autoReclaimEnabled,
                minutes = preferences.autoReclaimMinutes,
                // Same last-received-reading time the "Last data from watch" row uses.
                lastReadingMillis = { glucoseRepository.currentReadingMillis },
                onReclaimed = { de.mogluco.notification.WatchNotifications.sensorReclaimed(this) },
            )
            // Push the phone's display prefs (unit + target band, mg/dL) to the watch on connect
            // and whenever they change, so the watch mirrors them live (not just at bootstrap).
            CoroutineScope(Dispatchers.Default).launch {
                combine(
                    tk.glucodata.WatchLink.nodes,
                    preferences.unit,
                    preferences.targetLow,
                    preferences.targetHigh,
                ) { nodes, unit, low, high ->
                    UiPrefsPush(nodes.isNotEmpty(), unit == GlucoseUnit.MMOLL, low, high)
                }.collectLatest { push ->
                    if (push.hasWatch) {
                        delay(400) // debounce rapid slider changes; collectLatest cancels a pending push
                        tk.glucodata.WatchLink.pushUiPrefs(push.mmol, push.low, push.high)
                    }
                }
            }
            // Dismissing an alarm on the watch silences the phone's matching alarm.
            tk.glucodata.WatchLink.setAlarmDismissListener {
                de.mogluco.notification.AlarmController.onRemoteDismiss(this)
            }
            // On reconnect, fold in any readings the peer sends that we missed while disconnected,
            // and report how many were new so the Watch screen's "Re-sync" can show a real outcome.
            tk.glucodata.WatchLink.setHistoryListener { points ->
                tk.glucodata.WatchLink.notifyCatchupMerged(glucoseRepository.mergeBackfill(points))
            }
        }
        // Report the outcome of a manual "Pull history from sensor" (deep replay): the sensor's BLE
        // completion is async and the wireless-debug link is unreliable, so the app surfaces the
        // recovered-record count itself as a toast.
        tk.glucodata.Sensors.setDeepPullListener { _, records ->
            // The repository reload is triggered in the engine (core) side on completion; here we
            // clear the button spinner and surface the outcome to the user.
            de.mogluco.ui.settings.SensorPullState.inProgress.value = false
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val msg = when {
                    records < 0 -> "Couldn't reach the sensor to pull history — make sure it's connected to this phone."
                    records == 0 -> "Sensor replayed no stored readings for that span — those readings aren't on the sensor to recover."
                    else -> "Sensor replayed $records readings — filling in the graph now."
                }
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        // Evaluate glucose alarms (low/high/signal-loss) on every reading + a signal-loss watchdog.
        de.mogluco.notification.AlarmController.init(this)
        // Optional one-way export of new readings to Health Connect. No-op unless the user turned it
        // on in settings and granted the WRITE permission.
        de.mogluco.health.HealthConnectSync.start(this, glucoseRepository, preferences)
    }

    private companion object {
        // true  → synthetic sample data + simulated activation (for UI / notification / AOD testing).
        // false → live native engine + real Libre 3 NFC/BLE (device-in-the-loop).
        const val USE_SAMPLE_DATA = false
    }
}
