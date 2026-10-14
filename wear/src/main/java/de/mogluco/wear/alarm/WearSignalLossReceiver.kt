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
package de.mogluco.wear.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * The suspend-proof half of [WearAlarmController]'s signal-loss watchdog (port of the phone's
 * SignalLossReceiver). Its coroutine waits with a `delay`, which stands still while the watch is
 * suspended — and once readings stop, nothing else wakes it. So the same deadline is armed here
 * with AlarmManager; when it fires this just runs the controller's check.
 */
class WearSignalLossReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // AlarmManager's wake lock ends when this returns; bridge the moment until the ringing
        // service (started from the check) holds its own.
        runCatching {
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "juggluco:wear-loss-check")
                .acquire(5_000L)
        }
        WearAlarmController.onLossAlarm(context)
    }

    companion object {
        private const val TAG = "WearSignalLossReceiver"

        /** Arm the check for [atMillis] (wall clock), replacing any earlier time; 0 cancels it. */
        fun schedule(context: Context, atMillis: Long) {
            runCatching {
                val alarms = context.getSystemService(AlarmManager::class.java)
                val operation = PendingIntent.getBroadcast(
                    context, 0, Intent(context, WearSignalLossReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                if (atMillis <= 0L) {
                    alarms.cancel(operation)
                    return
                }
                // Exact where the system allows it without a further permission: while the app
                // is exempt from battery optimisation. Otherwise the inexact variant — it still
                // fires while the watch is idle, but the system may deliver it some minutes late.
                if (alarms.canScheduleExactAlarms()) {
                    try {
                        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
                        return
                    } catch (e: SecurityException) {
                        // The allowance went away in between — fall through to the inexact one.
                    }
                }
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
            }.onFailure { android.util.Log.w(TAG, "could not arm the signal-loss check", it) }
        }
    }
}
