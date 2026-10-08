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
package de.mogluco.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tk.glucodata.Engine

/** Which color theme the user wants the app drawn in. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Vertical-position presets for the always-on-display overlay. [percent] is the position as a
 * percentage of screen height (0 = top, 100 = bottom) that the preset sets the fine slider to.
 */
enum class AodPosition(val label: String, val percent: Float) {
    TOP("Top", 12f), CENTER("Center", 50f), BOTTOM("Bottom", 88f)
}

/** Vibration patterns for alarms; [timings] are on/off millis pairs passed to the Vibrator. */
enum class VibrationPattern(val label: String, val timings: LongArray) {
    SHORT("Short", longArrayOf(0, 250)),
    LONG("Long", longArrayOf(0, 800)),
    DOUBLE("Double", longArrayOf(0, 250, 200, 250)),
    ESCALATING("Wave", longArrayOf(0, 150, 150, 300, 150, 600)),
}

/**
 * App-level UI preferences for the redesigned Compose UI, persisted in [SharedPreferences]
 * and exposed as [StateFlow]s so screens react immediately. These are the display/behaviour
 * settings the new UI owns (unit, theme, which lines to show, target band, broadcast,
 * notification …). Engine-owned values (real alarm thresholds) still flow through
 * [SettingsRepository]; here we keep the user's *display* target range and preferences.
 *
 * The default unit is mg/dL per the user's preference.
 *
 * Every preference is declared once through a typed `…Pref(key, default)` helper. The helper
 * is the single source of truth: it seeds the flow, registers the key's type (so a backup can
 * be coerced back without guessing from the string content) and a reload closure (so
 * [importAll] can refresh every flow). Adding a setting means one line and nothing else.
 *
 * Implements [DisplayPrefs] — the subset the shared glucose repositories mirror.
 */
class AppPreferences(context: Context) : DisplayPrefs {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("juggluco_ui", Context.MODE_PRIVATE)

    private enum class PrefType { BOOL, INT, LONG, FLOAT, STRING }

    private class PrefSpec(val key: String, val type: PrefType, val reload: () -> Unit)

    /** One entry per preference, in declaration order; drives import coercion + reload. */
    private val specs = mutableListOf<PrefSpec>()
    private val specByKey by lazy { specs.associateBy { it.key } }

    private val _themeMode = enumPref("theme", ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _unit = enumPref("unit", GlucoseUnit.MGDL)
    override val unit: StateFlow<GlucoseUnit> = _unit.asStateFlow()

    private val _readingDisplay = enumPref("reading_display", ReadingDisplay.BOTH)
    override val readingDisplay: StateFlow<ReadingDisplay> = _readingDisplay.asStateFlow()

    private val _showTargetBand = boolPref("target_band", true)
    val showTargetBand: StateFlow<Boolean> = _showTargetBand.asStateFlow()

    private val _targetLow = floatPref("target_low", 70f)
    override val targetLow: StateFlow<Float> = _targetLow.asStateFlow()

    private val _targetHigh = floatPref("target_high", 180f)
    override val targetHigh: StateFlow<Float> = _targetHigh.asStateFlow()

    private val _xdrip = boolPref("xdrip", false)
    val xdripBroadcast: StateFlow<Boolean> = _xdrip.asStateFlow()

    private val _persistentNotification = boolPref("persist_notif", true)
    val persistentNotification: StateFlow<Boolean> = _persistentNotification.asStateFlow()

    // Optional one-way export of new readings to Health Connect. Off by default; permission is only
    // requested when the user turns it on (see HealthConnectSync + SettingsScreen).
    private val _healthConnect = boolPref("health_connect", false)
    val healthConnectEnabled: StateFlow<Boolean> = _healthConnect.asStateFlow()

    private val _lockscreen = boolPref("lockscreen", true)
    val showOnLockscreen: StateFlow<Boolean> = _lockscreen.asStateFlow()

    private val _accountId = stringPref("account_id", "")
    val accountId: StateFlow<String> = _accountId.asStateFlow()

    private val _smoothing = boolPref("smoothing", true)
    override val smoothing: StateFlow<Boolean> = _smoothing.asStateFlow()

    private val _smoothingAlpha = floatPref("smoothing_alpha", 0.4f)
    override val smoothingAlpha: StateFlow<Float> = _smoothingAlpha.asStateFlow()

    private val _gapThresholdMinutes = intPref("gap_threshold_min", 20)
    override val gapThresholdMinutes: StateFlow<Int> = _gapThresholdMinutes.asStateFlow()

    // Which logging options the user wants (FAB + legend + graph). Finer than category: carbs vs
    // dextrose, slow vs fast insulin toggle independently. Default: all.
    private val _enabledLogKinds = kotlinx.coroutines.flow.MutableStateFlow(
        prefs.getStringSet("log_kinds", null)
            ?.mapNotNull { runCatching { LogKind.valueOf(it) }.getOrNull() }?.toSet()
            ?: LogKind.entries.toSet(),
    )
    val enabledLogKinds: StateFlow<Set<LogKind>> = _enabledLogKinds.asStateFlow()

    fun setLogKindEnabled(kind: LogKind, enabled: Boolean) {
        val updated = if (enabled) _enabledLogKinds.value + kind else _enabledLogKinds.value - kind
        prefs.edit().putStringSet("log_kinds", updated.map { it.name }.toSet()).apply()
        _enabledLogKinds.value = updated
    }

    private val _allowLandscape = boolPref("allow_landscape", true)
    val allowLandscape: StateFlow<Boolean> = _allowLandscape.asStateFlow()

    // --- alarms (target range doubles as the alarm range) ---
    private val _lowAlarm = boolPref("alarm_low", true)
    val lowAlarmEnabled: StateFlow<Boolean> = _lowAlarm.asStateFlow()

    private val _highAlarm = boolPref("alarm_high", true)
    val highAlarmEnabled: StateFlow<Boolean> = _highAlarm.asStateFlow()

    private val _signalLoss = boolPref("alarm_loss", true)
    val signalLossAlarm: StateFlow<Boolean> = _signalLoss.asStateFlow()

    // Signal-loss delay (minutes) shared by the signal-loss alarm AND the signal-loss notification.
    private val _signalLossMinutes = intPref("signal_loss_min", 16)
    val signalLossMinutes: StateFlow<Int> = _signalLossMinutes.asStateFlow()

    // A quiet notification (not an alarm) when readings stop for [signalLossMinutes].
    private val _signalLossNotification = boolPref("signal_loss_notif", false)
    val signalLossNotification: StateFlow<Boolean> = _signalLossNotification.asStateFlow()

    // Ring through Do-Not-Disturb: while an alarm rings, the service lifts the interruption
    // filter and the alarm channels are marked DND-bypassing. Both need the user-granted
    // notification-policy access, so the settings row pairs this with the access grant.
    private val _overrideDnd = boolPref("alarm_override_dnd", false)
    val alarmOverrideDnd: StateFlow<Boolean> = _overrideDnd.asStateFlow()

    private val _repeatMinutes = intPref("alarm_repeat", 15)
    val alarmRepeatMinutes: StateFlow<Int> = _repeatMinutes.asStateFlow()

    private val _reminderRepeat = intPref("reminder_repeat", 30)
    val reminderRepeatMinutes: StateFlow<Int> = _reminderRepeat.asStateFlow()

    private val _alarmTimeoutSeconds = intPref("alarm_timeout", 60)
    val alarmTimeoutSeconds: StateFlow<Int> = _alarmTimeoutSeconds.asStateFlow()

    private val _vibrate = boolPref("alarm_vibrate", true)
    val alarmVibrate: StateFlow<Boolean> = _vibrate.asStateFlow()

    private val _vibrationPattern = enumPref("vibration_pattern", VibrationPattern.DOUBLE)
    val vibrationPattern: StateFlow<VibrationPattern> = _vibrationPattern.asStateFlow()

    private val _sound = boolPref("alarm_sound", true)
    val alarmSound: StateFlow<Boolean> = _sound.asStateFlow()

    private val _soundUri = stringPref("alarm_sound_uri", "")
    val alarmSoundUri: StateFlow<String> = _soundUri.asStateFlow()

    private val _soundTitle = stringPref("alarm_sound_title", "Default")
    val alarmSoundTitle: StateFlow<String> = _soundTitle.asStateFlow()

    private val _alwaysOn = boolPref("always_on", false)
    val alwaysOnDisplay: StateFlow<Boolean> = _alwaysOn.asStateFlow()

    // Fine vertical position of the AOD overlay as a percentage of screen height (0 = top,
    // 100 = bottom). The Top/Center/Bottom presets set this to their [AodPosition.percent].
    private val _aodPositionPercent = floatPref("aod_position_percent", AodPosition.TOP.percent)
    val aodPositionPercent: StateFlow<Float> = _aodPositionPercent.asStateFlow()

    private val _language = stringPref("language", "system")
    val language: StateFlow<String> = _language.asStateFlow()

    // --- Watch (phone↔watch sensor transfer) ---
    // Opt-in: when the watch owns the sensor and has been silent + unreachable for
    // autoReclaimMinutes, the phone automatically takes the connection back.
    private val _autoReclaim = boolPref("watch_auto_reclaim", false)
    val autoReclaimEnabled: StateFlow<Boolean> = _autoReclaim.asStateFlow()

    private val _autoReclaimMinutes = intPref("watch_auto_reclaim_min", 20)
    val autoReclaimMinutes: StateFlow<Int> = _autoReclaimMinutes.asStateFlow()

    // The watch's alarm timings, configured here and pushed to the watch (thresholds stay synced
    // via the target band). Repeat 0 = no repeat, matching the phone.
    private val _watchAlarmRepeat = intPref("watch_alarm_repeat", 15)
    val watchAlarmRepeat: StateFlow<Int> = _watchAlarmRepeat.asStateFlow()
    private val _watchAlarmSnooze = intPref("watch_alarm_snooze", 30)
    val watchAlarmSnooze: StateFlow<Int> = _watchAlarmSnooze.asStateFlow()
    // Seconds the WATCH alarm rings; 0 = until dismissed (default), else 15..600.
    private val _watchAlarmTimeout = intPref("watch_alarm_timeout", 0)
    val watchAlarmTimeout: StateFlow<Int> = _watchAlarmTimeout.asStateFlow()

    // Epoch millis of the most recent glucose reading (0 = none yet). Not a user-facing setting:
    // the alarm path persists it so signal-loss detection can span a process restart.
    private val _lastReadingEpochMillis = longPref("last_reading_ms", 0L)
    val lastReadingEpochMillis: StateFlow<Long> = _lastReadingEpochMillis.asStateFlow()

    // --- setters ---
    fun setLowAlarmEnabled(v: Boolean) = putBool("alarm_low", v) { _lowAlarm.value = v }
    fun setHighAlarmEnabled(v: Boolean) = putBool("alarm_high", v) { _highAlarm.value = v }
    fun setSignalLossAlarm(v: Boolean) = putBool("alarm_loss", v) { _signalLoss.value = v }
    fun setSignalLossMinutes(v: Int) = putInt("signal_loss_min", v) { _signalLossMinutes.value = v }
    fun setSignalLossNotification(v: Boolean) = putBool("signal_loss_notif", v) { _signalLossNotification.value = v }
    fun setAlarmOverrideDnd(v: Boolean) = putBool("alarm_override_dnd", v) { _overrideDnd.value = v }
    fun setAlarmRepeatMinutes(v: Int) = putInt("alarm_repeat", v) { _repeatMinutes.value = v }
    fun setReminderRepeatMinutes(v: Int) = putInt("reminder_repeat", v) { _reminderRepeat.value = v }
    fun setAlarmVibrate(v: Boolean) = putBool("alarm_vibrate", v) { _vibrate.value = v }
    fun setVibrationPattern(v: VibrationPattern) = put("vibration_pattern", v.name) { _vibrationPattern.value = v }
    fun setAlarmSound(v: Boolean) = putBool("alarm_sound", v) { _sound.value = v }
    fun setAlarmSound(uri: String, title: String) {
        prefs.edit().putString("alarm_sound_uri", uri).putString("alarm_sound_title", title).apply()
        _soundUri.value = uri; _soundTitle.value = title
    }
    fun setAlwaysOnDisplay(v: Boolean) = putBool("always_on", v) { _alwaysOn.value = v }
    fun setAodPositionPercent(v: Float) {
        val c = v.coerceIn(0f, 100f)
        putFloat("aod_position_percent", c) { _aodPositionPercent.value = c }
    }
    fun setLanguage(v: String) = put("language", v) { _language.value = v }
    fun setAutoReclaimEnabled(v: Boolean) = putBool("watch_auto_reclaim", v) { _autoReclaim.value = v }
    fun setAutoReclaimMinutes(v: Int) = putInt("watch_auto_reclaim_min", v) { _autoReclaimMinutes.value = v }
    fun setWatchAlarmRepeat(v: Int) = putInt("watch_alarm_repeat", v) { _watchAlarmRepeat.value = v }
    fun setWatchAlarmSnooze(v: Int) = putInt("watch_alarm_snooze", v) { _watchAlarmSnooze.value = v }
    fun setWatchAlarmTimeout(v: Int) = putInt("watch_alarm_timeout", v) { _watchAlarmTimeout.value = v }

    fun setThemeMode(v: ThemeMode) = put("theme", v.name) { _themeMode.value = v }
    fun setUnit(v: GlucoseUnit) = put("unit", v.name) { _unit.value = v }
    fun setReadingDisplay(v: ReadingDisplay) = put("reading_display", v.name) { _readingDisplay.value = v }
    fun setShowTargetBand(v: Boolean) = putBool("target_band", v) { _showTargetBand.value = v }
    fun setTargetLow(v: Float) = putFloat("target_low", v) { _targetLow.value = v }
    fun setTargetHigh(v: Float) = putFloat("target_high", v) { _targetHigh.value = v }
    fun setXdripBroadcast(v: Boolean) = putBool("xdrip", v) { _xdrip.value = v }
    fun setPersistentNotification(v: Boolean) = putBool("persist_notif", v) { _persistentNotification.value = v }
    fun setHealthConnectEnabled(v: Boolean) = putBool("health_connect", v) { _healthConnect.value = v }
    fun setShowOnLockscreen(v: Boolean) = putBool("lockscreen", v) { _lockscreen.value = v }
    fun setAccountId(v: String) = put("account_id", v) { _accountId.value = v }
    fun setSmoothing(v: Boolean) = putBool("smoothing", v) { _smoothing.value = v }
    fun setSmoothingAlpha(v: Float) = putFloat("smoothing_alpha", v) { _smoothingAlpha.value = v }
    fun setGapThresholdMinutes(v: Int) = putInt("gap_threshold_min", v) { _gapThresholdMinutes.value = v }
    fun setAllowLandscape(v: Boolean) = putBool("allow_landscape", v) { _allowLandscape.value = v }
    fun setAlarmTimeoutSeconds(v: Int) = putInt("alarm_timeout", v) { _alarmTimeoutSeconds.value = v }
    fun setLastReadingEpochMillis(millis: Long) =
        putLong("last_reading_ms", millis) { _lastReadingEpochMillis.value = millis }

    val targetRange: ClosedFloatingPointRange<Double>
        get() = _targetLow.value.toDouble().._targetHigh.value.toDouble()

    // --- pref declaration helpers: seed the flow + register type & reload in one place ---
    private fun boolPref(key: String, default: Boolean): MutableStateFlow<Boolean> {
        val flow = MutableStateFlow(prefs.getBoolean(key, default))
        specs += PrefSpec(key, PrefType.BOOL) { flow.value = prefs.getBoolean(key, default) }
        return flow
    }

    private fun intPref(key: String, default: Int): MutableStateFlow<Int> {
        val flow = MutableStateFlow(prefs.getInt(key, default))
        specs += PrefSpec(key, PrefType.INT) { flow.value = prefs.getInt(key, default) }
        return flow
    }

    private fun longPref(key: String, default: Long): MutableStateFlow<Long> {
        val flow = MutableStateFlow(prefs.getLong(key, default))
        specs += PrefSpec(key, PrefType.LONG) { flow.value = prefs.getLong(key, default) }
        return flow
    }

    private fun floatPref(key: String, default: Float): MutableStateFlow<Float> {
        val flow = MutableStateFlow(prefs.getFloat(key, default))
        specs += PrefSpec(key, PrefType.FLOAT) { flow.value = prefs.getFloat(key, default) }
        return flow
    }

    private fun stringPref(key: String, default: String): MutableStateFlow<String> {
        val flow = MutableStateFlow(prefs.getString(key, default) ?: default)
        specs += PrefSpec(key, PrefType.STRING) { flow.value = prefs.getString(key, default) ?: default }
        return flow
    }

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): MutableStateFlow<T> {
        val flow = MutableStateFlow(readEnum(key, default))
        specs += PrefSpec(key, PrefType.STRING) { flow.value = readEnum(key, default) }
        return flow
    }

    private inline fun put(key: String, value: String, crossinline after: () -> Unit) {
        prefs.edit().putString(key, value).apply(); after()
    }

    private inline fun putBool(key: String, value: Boolean, crossinline after: () -> Unit) {
        prefs.edit().putBoolean(key, value).apply(); after()
    }

    private inline fun putLong(key: String, value: Long, crossinline after: () -> Unit) {
        prefs.edit().putLong(key, value).apply(); after()
    }

    private inline fun putFloat(key: String, value: Float, crossinline after: () -> Unit) {
        prefs.edit().putFloat(key, value).apply(); after()
    }

    private inline fun putInt(key: String, value: Int, crossinline after: () -> Unit) {
        prefs.edit().putInt(key, value).apply(); after()
    }

    private inline fun <reified T : Enum<T>> readEnum(key: String, default: T): T =
        prefs.getString(key, null)?.let { name ->
            runCatching { enumValueOf<T>(name) }.getOrNull()
        } ?: default

    /** All persisted settings as strings, for the backup file. */
    fun exportAll(): Map<String, String> =
        prefs.all.entries.associate { it.key to it.value.toString() }

    /**
     * Restore settings from a backup. The value type comes from the schema (not from guessing
     * at the string content), so a string setting whose value happens to look numeric/boolean
     * — e.g. an all-digit LibreView `account_id` — is restored as a String rather than crashing
     * a later `getString` with a ClassCastException.
     */
    fun importAll(values: Map<String, String>) {
        val e = prefs.edit()
        values.forEach { (k, v) ->
            when (specByKey[k]?.type) {
                PrefType.BOOL -> e.putBoolean(k, v.toBoolean())
                PrefType.INT -> v.toIntOrNull()?.let { e.putInt(k, it) }
                PrefType.LONG -> v.toLongOrNull()?.let { e.putLong(k, it) }
                PrefType.FLOAT -> v.toFloatOrNull()?.let { e.putFloat(k, it) }
                // STRING, or an unknown/legacy key we have no type for: keep it as a string.
                PrefType.STRING, null -> e.putString(k, v)
            }
        }
        e.apply()
        reloadFromPrefs()
    }

    /** Re-emit every flow from the current SharedPreferences (after a bulk import). */
    private fun reloadFromPrefs() = specs.forEach { it.reload() }

    /**
     * One-time, on first native launch: adopt the engine's configured display unit + target band so
     * a user whose engine is already set to mmol/L with a custom target doesn't have to reconfigure
     * the redesigned UI by hand (it otherwise defaults to mg/dL, 70–180). Thereafter the Compose
     * settings own these — never re-seeded, so a later user override is preserved. The engine
     * returns the target in the DISPLAY unit, so convert it back to mg/dL, the unit these fields
     * store.
     */
    fun seedDisplayFromEngineOnce() {
        if (prefs.getBoolean(KEY_ENGINE_SEEDED, false)) return
        if (!Engine.isStarted()) return // retry next launch once the engine is up
        runCatching {
            val mmol = Engine.unitIsMmol()
            setUnit(if (mmol) GlucoseUnit.MMOLL else GlucoseUnit.MGDL)
            val factor = if (mmol) MGDL_PER_MMOL else 1f
            val low = Engine.targetLow() * factor
            val high = Engine.targetHigh() * factor
            if (low > 0f && high > low) {
                setTargetLow(low)
                setTargetHigh(high)
            }
        }
        prefs.edit().putBoolean(KEY_ENGINE_SEEDED, true).apply()
    }

    private companion object {
        /** Marks that we've adopted the engine's unit/target once (first native launch). */
        const val KEY_ENGINE_SEEDED = "engine_seeded_once"

        /** mg/dL per mmol/L — matches GlucoseUnit.fromMgdl's 18.0182 divisor. */
        const val MGDL_PER_MMOL = 18.0182f
    }
}
