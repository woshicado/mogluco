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
/*      Returned by Natives.lastglucose(). Field layout is fixed by the native side  */
/*      (curve/javacurve.cpp constructs it via the (J,String,String,F,I,I) ctor).    */

package tk.glucodata;

import androidx.annotation.Keep;

// @Keep: constructed only from native code, so R8 must not strip or rename it.
@Keep
public final class strGlucose {
    public long time;
    public String value;
    public String sensorid;
    public float rate;
    public int index;
    public int sensorgen2;

    public strGlucose(long time, String value, String sensorid, float rate, int index, int gen) {
        this.time = time;
        this.value = value;
        this.sensorid = sensorid;
        this.rate = rate;
        this.index = index;
        this.sensorgen2 = gen;
    }
}
