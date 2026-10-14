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
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings half of the full backup: what [AppPreferences.exportAll] puts in the file, what
 * [AppPreferences.importAll] writes back, and that a device which already restored a backup with
 * the old import (every unknown key written as a String) starts again instead of throwing a
 * ClassCastException on each launch.
 *
 * Runs on the JVM against [FakePrefs], which type-checks its getters like the platform
 * SharedPreferences does. The gzip/JSON file layer (DataExporter.writeBackup/readBackup) is not
 * exercised here — org.json is an Android stub in unit tests.
 */
class AppPreferencesBackupTest {

    // --- the pure key/type mapping ---

    @Test
    fun `backup strings coerce to the stored type or to null`() {
        assertEquals(true, coerceBackupValue(PrefType.BOOL, "true"))
        assertEquals(false, coerceBackupValue(PrefType.BOOL, "false"))
        // Not a boolean: skipped, never read as false (that would switch an alarm off).
        assertNull(coerceBackupValue(PrefType.BOOL, "1"))
        assertEquals(20, coerceBackupValue(PrefType.INT, "20"))
        assertNull(coerceBackupValue(PrefType.INT, "20.5"))
        assertEquals(1_786_000_000_000L, coerceBackupValue(PrefType.LONG, "1786000000000"))
        assertEquals(0.4f, coerceBackupValue(PrefType.FLOAT, "0.4"))
        assertNull(coerceBackupValue(PrefType.FLOAT, "NaN"))
        assertNull(coerceBackupValue(PrefType.FLOAT, "abc"))
        // An all-digit account id stays the String it is stored as.
        assertEquals("1234567", coerceBackupValue(PrefType.STRING, "1234567"))
    }

    @Test
    fun `a string set round-trips and the old toString form is still read`() {
        val written = backupString(setOf("CARBS", "BLOOD"))
        assertEquals("BLOOD,CARBS", written)
        assertEquals(setOf("BLOOD", "CARBS"), coerceBackupValue(PrefType.STRING_SET, written))
        assertEquals(emptySet<String>(), coerceBackupValue(PrefType.STRING_SET, backupString(emptySet<String>())))
        // What a backup made before the fix contains: Set.toString().
        assertEquals(setOf("BLOOD", "CARBS"), coerceBackupValue(PrefType.STRING_SET, "[CARBS, BLOOD]"))
        assertEquals(emptySet<String>(), coerceBackupValue(PrefType.STRING_SET, "[]"))
    }

    @Test
    fun `only schema keys are exported and imported`() {
        val schema = mapOf("alarm_low" to PrefType.BOOL, "alarm_repeat" to PrefType.INT, "account_id" to PrefType.STRING)
        val stored = mapOf(
            "alarm_low" to false, "alarm_repeat" to 5, "account_id" to "42",
            "engine_seeded_once" to true, "last_reading_ms" to 99L,
        )
        val backup = settingsToBackup(stored, schema)
        assertEquals(mapOf("alarm_low" to "false", "alarm_repeat" to "5", "account_id" to "42"), backup)

        val restored = settingsFromBackup(
            backup + mapOf("engine_seeded_once" to "true", "legacy" to "x", "alarm_repeat" to "soon"),
            schema,
        )
        // Unknown keys and the unparseable int are dropped; the rest carry their real type.
        assertEquals(mapOf<String, Any>("alarm_low" to false, "account_id" to "42"), restored)
    }

    // --- AppPreferences over a fake store ---

    @Test
    fun `export leaves out device state and writes the log kinds as a list`() {
        val store = FakePrefs()
        val prefs = AppPreferences(FakeContext(store))
        prefs.setLowAlarmEnabled(false)
        prefs.setAccountId("1234567")
        prefs.setLastReadingEpochMillis(1_786_000_000_000L)
        prefs.setLogKindEnabled(LogKind.DEXTROSE, false)
        store.map["engine_seeded_once"] = true

        val backup = prefs.exportAll()

        assertEquals("false", backup["alarm_low"])
        assertEquals("1234567", backup["account_id"])
        assertEquals("ACTIVITY,BLOOD,CARBS,FAST_INSULIN,SLOW_INSULIN", backup["log_kinds"])
        assertFalse("engine_seeded_once" in backup)
        assertFalse("last_reading_ms" in backup)
    }

    @Test
    fun `a backup restores every setting with its type on another device`() {
        val source = FakePrefs()
        AppPreferences(FakeContext(source)).apply {
            setUnit(GlucoseUnit.MMOLL)
            setTargetLow(80f)
            setHighAlarmEnabled(false)
            setAlarmRepeatMinutes(5)
            setAccountId("1234567")
            setLogKindEnabled(LogKind.ACTIVITY, false)
            setLastReadingEpochMillis(1_786_000_000_000L)
        }
        source.map["engine_seeded_once"] = true
        val backup = AppPreferences(FakeContext(source)).exportAll()

        val target = FakePrefs()
        target.map["engine_seeded_once"] = true
        target.map["last_reading_ms"] = 5L
        val restored = AppPreferences(FakeContext(target))
        restored.importAll(backup)

        // The settings arrive typed; the target's own device state is untouched.
        (source.map - "last_reading_ms").forEach { (key, value) -> assertEquals(key, value, target.map[key]) }
        assertEquals(5L, target.map["last_reading_ms"])
        assertEquals(5L, restored.lastReadingEpochMillis.value)
        // …and the flows follow without a restart.
        assertEquals(GlucoseUnit.MMOLL, restored.unit.value)
        assertEquals(80f, restored.targetLow.value)
        assertFalse(restored.highAlarmEnabled.value)
        assertEquals(5, restored.alarmRepeatMinutes.value)
        assertEquals("1234567", restored.accountId.value)
        assertEquals(LogKind.entries.toSet() - LogKind.ACTIVITY, restored.enabledLogKinds.value)
    }

    @Test
    fun `a setting still at its default is in the backup and comes back`() {
        // Nothing was ever stored for the unit when the backup was made…
        val backup = AppPreferences(FakeContext(FakePrefs())).exportAll()
        assertEquals("MGDL", backup["unit"])
        // …so a restore must still undo a later change of it.
        val prefs = AppPreferences(FakeContext(FakePrefs())).apply { setUnit(GlucoseUnit.MMOLL) }
        prefs.importAll(backup)
        assertEquals(GlucoseUnit.MGDL, prefs.unit.value)
    }

    @Test
    fun `a backup from the old version restores without poisoning the store`() {
        // Exactly what the old exportAll produced: every key, each value's toString().
        val oldBackup = mapOf(
            "engine_seeded_once" to "true",
            "last_reading_ms" to "1786000000000",
            "log_kinds" to "[CARBS, BLOOD]",
            "alarm_low" to "false",
            "account_id" to "1234567",
            "some_legacy_key" to "x",
        )
        val store = FakePrefs()
        store.map["engine_seeded_once"] = true
        AppPreferences(FakeContext(store)).importAll(oldBackup)

        assertEquals(true, store.map["engine_seeded_once"])
        assertFalse("last_reading_ms" in store.map)
        assertFalse("some_legacy_key" in store.map)
        assertEquals(setOf("BLOOD", "CARBS"), store.map["log_kinds"])
        assertEquals(false, store.map["alarm_low"])
        assertEquals("1234567", store.map["account_id"])

        // The next process start: construct again and run the startup read that used to throw.
        val next = AppPreferences(FakeContext(store))
        next.seedDisplayFromEngineOnce()
        assertEquals(setOf(LogKind.BLOOD, LogKind.CARBS), next.enabledLogKinds.value)
        assertFalse(next.lowAlarmEnabled.value)
    }

    @Test
    fun `a store already poisoned by the old import heals on the next start`() {
        // What the old importAll left behind: keys it had no type for, written as Strings.
        val store = FakePrefs()
        store.map["engine_seeded_once"] = "true"
        store.map["log_kinds"] = "[CARBS, BLOOD]"
        store.map["unit"] = "MMOLL"

        val prefs = AppPreferences(FakeContext(store))
        // Still "seeded": healing to the default would re-adopt the engine's unit and target band.
        prefs.seedDisplayFromEngineOnce()

        assertEquals(setOf(LogKind.BLOOD, LogKind.CARBS), prefs.enabledLogKinds.value)
        assertEquals(GlucoseUnit.MMOLL, prefs.unit.value)
        assertEquals(true, store.map["engine_seeded_once"])
        assertEquals(setOf("BLOOD", "CARBS"), store.map["log_kinds"])
    }

    @Test
    fun `a wrongly typed entry that cannot be read is dropped for the default`() {
        val store = FakePrefs()
        store.map["alarm_repeat"] = "soon"
        store.map["alarm_low"] = 1
        store.map["target_low"] = "80"
        store.map["theme"] = true

        val prefs = AppPreferences(FakeContext(store))

        assertEquals(15, prefs.alarmRepeatMinutes.value)
        assertFalse("alarm_repeat" in store.map)
        assertTrue(prefs.lowAlarmEnabled.value)
        assertFalse("alarm_low" in store.map)
        // Readable as the declared type: kept, and stored properly from now on.
        assertEquals(80f, prefs.targetLow.value)
        assertEquals(80f, store.map["target_low"])
        assertEquals(ThemeMode.SYSTEM, prefs.themeMode.value)
    }
}

/** A Context good for exactly what [AppPreferences] asks of one: its SharedPreferences. */
private class FakeContext(private val store: SharedPreferences) : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = store
}

/**
 * In-memory SharedPreferences whose typed getters cast like the platform implementation — a
 * value stored under another type throws ClassCastException, which is the failure under test.
 */
private class FakePrefs : SharedPreferences {
    val map = mutableMapOf<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?): String? = map[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as Set<String>?)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as Boolean? ?: defValue
    override fun contains(key: String): Boolean = key in map
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableListOf<() -> Unit>()

        private fun set(key: String, value: Any?) = apply {
            pending += { if (value == null) map.remove(key) else map[key] = value }
        }

        override fun putString(key: String, value: String?) = set(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = set(key, values?.toSet())
        override fun putInt(key: String, value: Int) = set(key, value)
        override fun putLong(key: String, value: Long) = set(key, value)
        override fun putFloat(key: String, value: Float) = set(key, value)
        override fun putBoolean(key: String, value: Boolean) = set(key, value)
        override fun remove(key: String) = set(key, null)
        override fun clear() = apply { pending += { map.clear() } }
        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            pending.forEach { it() }
            pending.clear()
        }
    }
}
