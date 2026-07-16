/*      This file is part of Mogluco, a Material 3 redesign fork of                  */
/*      Juggluco, for Freestyle Libre 2 and 3 glucose sensors.                       */
/*                                                                                   */
/*      Copyright (C) 2026 Joshua Meyer                                              */
/*      Portions derived from Juggluco, Copyright (C) 2021                           */
/*      Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>.                           */
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
/*      Phase 0 load-time stub — see JniLoadStubs.java.                              */
/*      Eversense is out of scope (Libre3-only redesign); kept only to satisfy      */
/*      libg.so JNI_OnLoad (curve/javacurve.cpp).                                    */

package tk.glucodata;

class EverSense {
    static void broadcastglucose(int mgdl, float rate, long timeMillis) {}
}
