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
package de.mogluco.wear

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import de.mogluco.data.DisplayPrefs
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.ReadingDisplay
import tk.glucodata.Engine

/**
 * Watch-local preferences: the [DisplayPrefs] subset the shared repositories mirror, plus
 * the on-watch alarm switches. Deliberately much smaller than the phone's `AppPreferences` —
 * the watch is view-only; everything else is configured on the phone.
 *
 * Unit and target range are NOT user-editable on the watch: they follow the phone's engine
 * settings, which the mirror's /start bootstrap delivers into the native store —
 * [syncFromEngine] copies them out (on boot and on every Engine invalidate) so the watch
 * always renders and alarms in the user's real unit/band.
 *
 * Every preference is declared once through a typed pref helper (same pattern as the
 * phone's AppPreferences): the helper owns the key, so a flow and its setter can't drift
 * onto different keys.
 */
class WearPreferences(context: Context) : DisplayPrefs {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("juggluco_wear", Context.MODE_PRIVATE)

    /** A persisted preference: one key, one flow, one setter. */
    inner class BoolPref(private val key: String, default: Boolean) {
        private val state = MutableStateFlow(prefs.getBoolean(key, default))
        val flow: StateFlow<Boolean> = state.asStateFlow()
        fun set(v: Boolean) {
            prefs.edit().putBoolean(key, v).apply(); state.value = v
        }
    }

    inner class FloatPref(private val key: String, default: Float) {
        private val state = MutableStateFlow(prefs.getFloat(key, default))
        val flow: StateFlow<Float> = state.asStateFlow()
        fun set(v: Float) {
            prefs.edit().putFloat(key, v).apply(); state.value = v
        }
    }

    inner class LongPref(private val key: String, default: Long) {
        private val state = MutableStateFlow(prefs.getLong(key, default))
        val flow: StateFlow<Long> = state.asStateFlow()
        fun set(v: Long) {
            prefs.edit().putLong(key, v).apply(); state.value = v
        }
    }

    inner class IntPref(private val key: String, default: Int) {
        private val state = MutableStateFlow(prefs.getInt(key, default))
        val flow: StateFlow<Int> = state.asStateFlow()
        fun set(v: Int) {
            prefs.edit().putInt(key, v).apply(); state.value = v
        }
    }

    inner class EnumPref<T : Enum<T>>(
        private val key: String,
        private val default: T,
        private val values: Array<T>,
    ) {
        private val state = MutableStateFlow(read())
        val flow: StateFlow<T> = state.asStateFlow()
        private fun read(): T =
            prefs.getString(key, null)?.let { name -> values.firstOrNull { it.name == name } } ?: default
        fun set(v: T) {
            prefs.edit().putString(key, v.name).apply(); state.value = v
        }
    }

    // --- Display (mirrors the phone's engine settings via syncFromEngine) ---
    private val _unit = EnumPref("unit", GlucoseUnit.MGDL, GlucoseUnit.entries.toTypedArray())
    override val unit: StateFlow<GlucoseUnit> = _unit.flow

    private val _targetLow = FloatPref("target_low", 70f)
    override val targetLow: StateFlow<Float> = _targetLow.flow

    private val _targetHigh = FloatPref("target_high", 180f)
    override val targetHigh: StateFlow<Float> = _targetHigh.flow

    // The watch shows the calibrated trace only; BOTH is a phone-chart affordance.
    private val _readingDisplay =
        EnumPref("reading_display", ReadingDisplay.SMOOTHED, ReadingDisplay.entries.toTypedArray())
    override val readingDisplay: StateFlow<ReadingDisplay> = _readingDisplay.flow

    private val _smoothing = BoolPref("smoothing", true)
    override val smoothing: StateFlow<Boolean> = _smoothing.flow
    fun setSmoothing(v: Boolean) = _smoothing.set(v)

    private val _smoothingAlpha = FloatPref("smoothing_alpha", 0.4f)
    override val smoothingAlpha: StateFlow<Float> = _smoothingAlpha.flow

    private val _gapThresholdMinutes = IntPref("gap_threshold_min", 20)
    override val gapThresholdMinutes: StateFlow<Int> = _gapThresholdMinutes.flow

    // --- On-watch alarms (rung by the watch itself, phone nearby or not) ---
    private val _lowAlarm = BoolPref("alarm_low", true)
    val lowAlarmEnabled: StateFlow<Boolean> = _lowAlarm.flow

    private val _highAlarm = BoolPref("alarm_high", true)
    val highAlarmEnabled: StateFlow<Boolean> = _highAlarm.flow

    private val _signalLoss = BoolPref("alarm_loss", true)
    val signalLossAlarm: StateFlow<Boolean> = _signalLoss.flow

    // Alarm timings — configurable from the phone's Watch settings (pushed over /wearalarm);
    // default to the previous fixed values. Repeat 0 = no automatic repeat (as on the phone).
    private val _wearRepeatMin = LongPref("wear_repeat_min", 15)
    val wearRepeatMinutes: StateFlow<Long> = _wearRepeatMin.flow
    private val _wearSnoozeMin = LongPref("wear_snooze_min", 30)
    val wearSnoozeMinutes: StateFlow<Long> = _wearSnoozeMin.flow
    // 0 = ring until dismissed (default): an unattended night-time alarm must keep going.
    private val _wearTimeoutSec = LongPref("wear_timeout_sec", 0)
    val wearTimeoutSeconds: StateFlow<Long> = _wearTimeoutSec.flow

    fun applyWearAlarm(repeatMin: Int, snoozeMin: Int, timeoutSec: Int) {
        _wearRepeatMin.set(repeatMin.toLong())
        _wearSnoozeMin.set(snoozeMin.toLong())
        _wearTimeoutSec.set(timeoutSec.toLong())
    }

    // The ongoing keep-alive notification / watch-face chip. Off = minimal (the foreground service
    // still needs a notification, but no value chip on the watch face).
    private val _persistentNotification = BoolPref("persist_notif", true)
    val persistentNotification: StateFlow<Boolean> = _persistentNotification.flow
    fun setPersistentNotification(v: Boolean) = _persistentNotification.set(v)

    // One-time guard so the battery-optimization exemption is requested once, not every launch.
    private val _batteryOptAsked = BoolPref("battery_opt_asked", false)
    fun batteryOptAsked(): Boolean = _batteryOptAsked.flow.value
    fun setBatteryOptAsked() = _batteryOptAsked.set(true)

    private val _overrideDnd = BoolPref("alarm_override_dnd", false)
    val alarmOverrideDnd: StateFlow<Boolean> = _overrideDnd.flow

    private val _vibrate = BoolPref("alarm_vibrate", true)
    val alarmVibrate: StateFlow<Boolean> = _vibrate.flow

    private val _sound = BoolPref("alarm_sound", true)
    val alarmSound: StateFlow<Boolean> = _sound.flow

    private val _lastReadingEpochMillis = LongPref("last_reading_ms", 0L)
    val lastReadingEpochMillis: StateFlow<Long> = _lastReadingEpochMillis.flow

    // True once the phone has pushed its display prefs (unit + target band) at least once. From
    // then on the phone is authoritative for these, so syncFromEngine stops overriding them.
    private val _uiPrefsReceived = BoolPref("uiprefs_received", false)

    fun setLowAlarmEnabled(v: Boolean) = _lowAlarm.set(v)
    fun setHighAlarmEnabled(v: Boolean) = _highAlarm.set(v)
    fun setSignalLossAlarm(v: Boolean) = _signalLoss.set(v)
    fun setAlarmOverrideDnd(v: Boolean) = _overrideDnd.set(v)
    fun setAlarmVibrate(v: Boolean) = _vibrate.set(v)
    fun setAlarmSound(v: Boolean) = _sound.set(v)
    fun setLastReadingEpochMillis(millis: Long) = _lastReadingEpochMillis.set(millis)

    /**
     * Copy unit + target band out of the native engine (delivered by the phone over the
     * mirror's /start bootstrap). Call after Engine.start() and on every Engine invalidate.
     * No-ops on unbooted/implausible values so a cold engine can't wipe the last-known band.
     */
    fun syncFromEngine() {
        runCatching {
            if (!Engine.isStarted()) return
            // Once the phone has pushed its display prefs, they're authoritative — don't let the
            // engine's (possibly different) unit/target override the user's actual phone settings.
            if (_uiPrefsReceived.flow.value) return
            val mmol = Engine.unitIsMmol()
            val unitValue = if (mmol) GlucoseUnit.MMOLL else GlucoseUnit.MGDL
            if (_unit.flow.value != unitValue) _unit.set(unitValue)
            // Engine.targetLow()/targetHigh() return the value in the user's DISPLAY unit (mmol/L
            // for a mmol user), but DisplayPrefs.targetLow/High — and every watch consumer (the
            // alarm thresholds, the chart band, the tile) — are contractually mg/dL, matching the
            // raw calibratedMgdl readings. Convert back to mg/dL so a mmol/L user's LOW alarm
            // actually fires (it never did while ~3.9 was compared against ~65 mg/dL readings).
            val factor = if (mmol) MGDL_PER_MMOL else 1f
            val low = Engine.targetLow() * factor
            val high = Engine.targetHigh() * factor
            if (low > 0f && high > low) {
                if (_targetLow.flow.value != low) _targetLow.set(low)
                if (_targetHigh.flow.value != high) _targetHigh.set(high)
            }
        }
    }

    /**
     * Apply display prefs the phone pushed over the mirror (/uiprefs). [lowMgdl]/[highMgdl] are
     * mg/dL — the canonical unit these fields store. Marks the phone authoritative so
     * [syncFromEngine] no longer overrides unit/target.
     */
    fun applyUiPrefs(unitMmol: Boolean, lowMgdl: Float, highMgdl: Float) {
        if (!_uiPrefsReceived.flow.value) _uiPrefsReceived.set(true)
        val unitValue = if (unitMmol) GlucoseUnit.MMOLL else GlucoseUnit.MGDL
        if (_unit.flow.value != unitValue) _unit.set(unitValue)
        if (lowMgdl > 0f && highMgdl > lowMgdl) {
            if (_targetLow.flow.value != lowMgdl) _targetLow.set(lowMgdl)
            if (_targetHigh.flow.value != highMgdl) _targetHigh.set(highMgdl)
        }
    }

    private companion object {
        /** mg/dL per mmol/L — matches GlucoseUnit.fromMgdl's 18.0182 divisor. */
        const val MGDL_PER_MMOL = 18.0182f
    }
}
