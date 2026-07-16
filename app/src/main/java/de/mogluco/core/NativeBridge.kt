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
package de.mogluco.core

import tk.glucodata.Natives

/**
 * Thin Kotlin entry to the native engine in :core. Phase 0 only proves the link; Phase 1
 * grows this into the data path that backs the native `GlucoseRepository`.
 */
object NativeBridge {

    /**
     * Calls a trivial native method to prove libg.so loaded and JNI symbols resolve.
     * Returns a human-readable status for the Phase 0 home banner.
     */
    fun linkStatus(): String = runCatching {
        "native engine linked · GL ES ${Natives.openglversion()}"
    }.getOrElse { e ->
        "native link FAILED · ${e.javaClass.simpleName}: ${e.message}"
    }
}
