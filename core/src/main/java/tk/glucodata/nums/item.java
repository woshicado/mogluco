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
/*      Event item carried from the native nums store into the UI.                  */
/*      Field ORDER is dictated by nums/numjava.cpp:                                 */
/*        NewObject(item, "(JIFI)V", num.time, num.mealptr, num.value, num.type)     */
/*      i.e. (time, mealptr, value, type) — not (time, type, value, extra).          */

package tk.glucodata.nums;

import androidx.annotation.Keep;

// @Keep: constructed only from native code, so R8 must not strip or rename it.
@Keep
public class item {
    public final long time;
    public final int mealptr;
    public final float value;
    public final int type;

    public item(long time, int mealptr, float value, int type) {
        this.time = time;
        this.mealptr = mealptr;
        this.value = value;
        this.type = type;
    }
}
