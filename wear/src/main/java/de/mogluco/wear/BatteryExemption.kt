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
package de.mogluco.wear

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Open a screen where the user can lift background restrictions; returns true once one launches.
 *
 * On Wear the battery-optimization intents (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS /
 * ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) resolve to a no-op stub (Google's
 * FakeSettingsActivity), so the app's own details page — which carries the real battery/background
 * controls — is tried first; the battery intents are kept only as a fallback for devices where they
 * do resolve. One copy shared by the startup prompt ([MainActivity]) and the settings card.
 */
@android.annotation.SuppressLint("BatteryLife")
fun requestBatteryExemption(context: Context): Boolean {
    val pkg = android.net.Uri.parse("package:${context.packageName}")
    val intents = listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )
    for (i in intents) {
        if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
    }
    return false
}
