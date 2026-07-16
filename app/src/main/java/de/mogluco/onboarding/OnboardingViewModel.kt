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
package de.mogluco.onboarding

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import de.mogluco.data.GlucoseReading

/** The guided steps for adding a Libre3 sensor — collapsing the legacy multi-screen setup. */
enum class OnboardingStep { Permissions, Scan, Activating, WarmingUp, Done, Error }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Permissions,
    val warmupEndMillis: Long? = null,
    val error: String? = null,
)

class OnboardingViewModel(
    private val activator: SensorActivator,
    // Live readings, so a takeover that reports a transient failure but actually starts streaming
    // can be recognised as success. Defaults to empty (e.g. previews).
    private val latestReadings: Flow<GlucoseReading?> = emptyFlow(),
    // Whether the engine now has an active sensor. A take-over/rescan REGISTERS the sensor
    // immediately even when the NFC step reports a transient error — that's the fast success signal,
    // so we don't have to block on the (much slower) first reading. Defaults to false (e.g. previews).
    private val sensorActive: () -> Boolean = { false },
    // Reflect the just-registered sensor in the home UI immediately (a "connecting" state), so it
    // doesn't look empty while the BLE handshake completes. Defaults to no-op (e.g. previews).
    private val onActivated: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /** True while the scan step is showing — the Activity enables NFC reader mode then. */
    val scanning: Boolean get() = _state.value.step == OnboardingStep.Scan

    fun onPermissionsResolved() {
        if (_state.value.step == OnboardingStep.Permissions) {
            _state.update { it.copy(step = OnboardingStep.Scan) }
        }
    }

    fun onTagDetected(tag: Tag) {
        // Ignore taps unless we're actively waiting to scan.
        if (_state.value.step != OnboardingStep.Scan) return
        _state.update { it.copy(step = OnboardingStep.Activating) }
        viewModelScope.launch {
            when (val result = activator.activate(tag)) {
                is ActivationResult.WarmingUp -> {
                    onActivated()
                    _state.update {
                        it.copy(step = OnboardingStep.WarmingUp, warmupEndMillis = result.warmupEndMillis)
                    }
                }
                ActivationResult.Active -> {
                    onActivated()
                    _state.update { it.copy(step = OnboardingStep.Done) }
                }
                is ActivationResult.Failed ->
                    // A Libre 3 take-over/rescan frequently reports a transient NFC failure at the
                    // handshake instant while the engine still REGISTERS the sensor (BLE then streams
                    // a moment later, often after a few handshake retries that can take a minute+).
                    // Success = a sensor is now active (immediate — no need to hold the phone or wait
                    // on the first reading), falling back to a short reading-wait if nothing
                    // registered. Definitive failures (terminated / ended / not a Libre 3) fail at once.
                    if (result.transient && (sensorActive() || awaitFreshReading())) {
                        onActivated()
                        _state.update { it.copy(step = OnboardingStep.Done) }
                    } else {
                        _state.update { it.copy(step = OnboardingStep.Error, error = result.reason) }
                    }
            }
        }
    }

    /**
     * Fallback for when no sensor registered yet: wait briefly for a non-stale reading timestamped
     * around now. A reading already present matches immediately; otherwise a short window is enough,
     * since a real take-over is caught by [sensorActive] instantly and doesn't reach here.
     */
    private suspend fun awaitFreshReading(timeoutMs: Long = 20_000L): Boolean {
        val since = System.currentTimeMillis()
        return withTimeoutOrNull(timeoutMs) {
            latestReadings.filterNotNull().first { r ->
                !r.isStale() && r.epochMillis >= since - 5 * 60_000L
            }
            true
        } ?: false
    }

    fun acknowledgeWarmup() {
        _state.update { it.copy(step = OnboardingStep.Done) }
    }

    fun retry() {
        _state.update { OnboardingUiState(step = OnboardingStep.Scan) }
    }
}
