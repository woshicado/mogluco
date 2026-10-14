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
    // The serials the engine has active right now. A take-over can REGISTER the sensor even when
    // the NFC step reports a transient error — a serial that wasn't there before the scan is the
    // fast success signal, so we don't have to block on the (much slower) first reading. Defaults
    // to none (e.g. previews).
    private val activeSensors: () -> Set<String> = { emptySet() },
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
            val activeBefore = activeSensors()
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
                    // A Libre 3 take-over can report a transient NFC failure at the handshake
                    // instant while the engine still REGISTERS the sensor (BLE then streams a moment
                    // later, often after a few handshake retries that can take a minute+).
                    // Success = the engine now has a sensor it did not have before the scan
                    // (immediate — no need to hold the phone or wait on the first reading); with no
                    // sensor active before, a short reading-wait as a fallback. Not merely "some
                    // sensor is active" or "a reading arrived": while the previous sensor is still
                    // streaming, both are true after a scan of the NEW one failed, and the app
                    // would report a sensor as set up that it never registered. Definitive failures
                    // (terminated / ended / not a Libre 3) fail at once.
                    if (result.transient && registeredSince(activeBefore, result.registeredSerial)) {
                        onActivated()
                        _state.update { it.copy(step = OnboardingStep.Done) }
                    } else {
                        _state.update { it.copy(step = OnboardingStep.Error, error = result.reason) }
                    }
            }
        }
    }

    /**
     * Whether the scan left the engine with its sensor: the one the engine says it registered
     * ([registeredSerial] — also a rescan of the sensor already in use, e.g. to take it back from a
     * watch), or one it did not have in [activeBefore].
     */
    private suspend fun registeredSince(activeBefore: Set<String>, registeredSerial: String?): Boolean {
        val active = activeSensors()
        if (registeredSerial != null && registeredSerial in active) return true
        if ((active - activeBefore).isNotEmpty()) return true
        return activeBefore.isEmpty() && awaitFreshReading()
    }

    /**
     * Fallback for when no sensor registered yet: wait briefly for a non-stale reading timestamped
     * around now. A reading already present matches immediately; otherwise a short window is enough,
     * since a real take-over is caught by [registeredSince] instantly and doesn't reach here.
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

    /**
     * Start the flow over, for when the screen is opened again: this view model outlives the
     * screen, so a finished (or failed) earlier run would otherwise still show its last step. A
     * run that is still under way is left alone — never reset an activation in mid-handshake.
     */
    fun restart() {
        _state.update {
            when (it.step) {
                OnboardingStep.WarmingUp, OnboardingStep.Done, OnboardingStep.Error -> OnboardingUiState()
                else -> it
            }
        }
    }
}
