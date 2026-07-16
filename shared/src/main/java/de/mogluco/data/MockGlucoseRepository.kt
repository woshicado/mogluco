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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Fake [GlucoseRepository] driven by [SampleData] so the redesigned UI runs (and can be
 * screenshotted) with no sensor. Unit / target range / reading display are read live from
 * the app's [DisplayPrefs]. A [cutoff] models "delete history before a date" so the Settings
 * action has a visible effect on the sample data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MockGlucoseRepository(
    private val prefs: DisplayPrefs,
) : GlucoseRepository {

    private val _sensorError = MutableStateFlow<SensorError?>(null)
    private val cutoff = MutableStateFlow(0L)
    // When set (e.g. after a backup restore), these replace the generated sample trace.
    private val imported = MutableStateFlow<List<GlucosePoint>?>(null)

    override val latest: Flow<GlucoseReading?> = flow {
        while (true) {
            emit(SampleData.latest())
            kotlinx.coroutines.delay(30_000)
        }
    }

    override val unit: Flow<GlucoseUnit> = prefs.unit

    override val targetRange: Flow<ClosedFloatingPointRange<Double>> =
        combine(prefs.targetLow, prefs.targetHigh) { lo, hi -> lo.toDouble()..hi.toDouble() }

    override val history: Flow<List<GlucosePoint>> =
        combine(cutoff, imported) { cut, imp -> cut to imp }.flatMapLatest { (cut, imp) ->
            flow {
                while (true) {
                    val base = imp ?: SampleData.history()
                    emit(base.filter { it.epochMillis >= cut })
                    kotlinx.coroutines.delay(60_000)
                }
            }
        }

    override val sensor: Flow<SensorInfo?> =
        _sensorError.map { err -> SampleData.activeSensor().copy(error = err) }

    override val currentSensor: SensorInfo?
        get() = SampleData.activeSensor().copy(error = _sensorError.value)

    override val readingDisplay: Flow<ReadingDisplay> = prefs.readingDisplay

    override fun deleteBefore(cutoffMillis: Long): Boolean {
        cutoff.value = cutoffMillis
        // Also trim any imported series so deletion applies to restored data too.
        imported.value = imported.value?.filter { it.epochMillis >= cutoffMillis }
        return true
    }

    override fun importHistory(points: List<GlucosePoint>): Boolean {
        cutoff.value = 0L
        imported.value = points
        return true
    }
}
