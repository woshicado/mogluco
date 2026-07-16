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
package de.mogluco.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Lifecycle-gated wall-clock ticker: emits the current time immediately and then every
 * [intervalMillis] while the activity is STARTED. On a watch the app spends most of its life
 * paused behind the watchface, and an unguarded `produceState { while(true) … }` keeps waking
 * the CPU there — repeatOnLifecycle stops the loop the moment the activity leaves STARTED and
 * restarts it (with a fresh timestamp) on return.
 */
@Composable
fun rememberLifecycleTicker(intervalMillis: Long): State<Long> {
    val lifecycleOwner = LocalLifecycleOwner.current
    return produceState(System.currentTimeMillis(), lifecycleOwner, intervalMillis) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(intervalMillis)
            }
        }
    }
}
