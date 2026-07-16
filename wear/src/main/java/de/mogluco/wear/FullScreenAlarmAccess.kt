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

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Whether an alarm can open its full-screen surface from the background.
 *
 * On Android 14 / Wear OS 5 (API 34+) the USE_FULL_SCREEN_INTENT grant is OFF by default for apps
 * without a declared alarm/calendar role. When it's off, a background alarm can't reliably start its
 * activity: the notification's full-screen intent degrades to a heads-up (a small notification icon),
 * the full-screen surface never comes forward, and — because our crown-dismiss rides
 * WearAlarmActivity.onUserLeaveHint — the alarm can't be dismissed with the crown either. Granting it
 * makes setFullScreenIntent bring the alarm activity up full-screen from the background by design.
 *
 * Below API 34 the permission is install-time, so this is always true.
 */
fun canUseFullScreenAlarm(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
    val nm = context.getSystemService(NotificationManager::class.java) ?: return true
    return runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true)
}

/**
 * Open the system screen where the user grants full-screen-intent access; returns true once one
 * launches. Shared by the startup prompt ([MainActivity]) and the settings card. No-op below API 34
 * (nothing to grant). Falls back to the app's details page on the off chance the dedicated screen
 * isn't handled.
 */
fun requestFullScreenAlarmAccess(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    val pkg = android.net.Uri.parse("package:${context.packageName}")
    val intents = listOf(
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
    )
    for (i in intents) {
        if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
    }
    return false
}
