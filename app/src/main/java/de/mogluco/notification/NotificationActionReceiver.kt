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
package de.mogluco.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the signal-loss notification's action buttons (reclaim sensor / dismiss). */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RECLAIM -> {
                intent.getStringExtra(EXTRA_NODE)?.let { runCatching { tk.glucodata.WatchLink.takeBackSensor(it) } }
                WatchNotifications.cancelSignalLoss(context)
            }
            ACTION_DISMISS -> WatchNotifications.cancelSignalLoss(context)
        }
    }

    companion object {
        const val ACTION_RECLAIM = "de.mogluco.action.RECLAIM_SENSOR"
        const val ACTION_DISMISS = "de.mogluco.action.DISMISS_SIGNAL_LOSS"
        const val EXTRA_NODE = "node"
    }
}
