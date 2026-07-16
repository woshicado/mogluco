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

import tk.glucodata.Engine

/** Alarm configuration; thresholds are in mg/dL (the engine's storage unit). */
data class AlarmConfig(
    val lowMgdl: Float,
    val highMgdl: Float,
    val lowEnabled: Boolean,
    val highEnabled: Boolean,
    val availableEnabled: Boolean,
    val lossEnabled: Boolean,
)

interface SettingsRepository {
    val unit: GlucoseUnit
    fun alarms(): AlarmConfig
    fun setAlarms(config: AlarmConfig)
}

class NativeSettingsRepository : SettingsRepository {

    override val unit: GlucoseUnit
        get() = if (runCatching { Engine.unitIsMmol() }.getOrDefault(false)) GlucoseUnit.MMOLL
        else GlucoseUnit.MGDL

    override fun alarms(): AlarmConfig {
        val s = Engine.alarmSettings()
        return AlarmConfig(
            lowMgdl = s.low,
            highMgdl = s.high,
            lowEnabled = s.lowEnabled,
            highEnabled = s.highEnabled,
            availableEnabled = s.availableEnabled,
            lossEnabled = s.lossEnabled,
        )
    }

    override fun setAlarms(config: AlarmConfig) {
        Engine.setAlarmSettings(
            Engine.AlarmSettings(
                config.lowMgdl,
                config.highMgdl,
                config.lowEnabled,
                config.highEnabled,
                config.availableEnabled,
                config.lossEnabled,
            ),
        )
    }
}
