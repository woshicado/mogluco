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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** One-off watch-link notifications (auto-reclaim event; signal-loss notification). */
object WatchNotifications {

    private const val CHANNEL_ID = "watch_link_v1"
    private const val NOTIF_ID = 0xB7
    private const val SIGNAL_LOSS_CHANNEL = "signal_loss_v1"
    private const val SIGNAL_LOSS_ID = 0xB8

    fun sensorReclaimed(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Watch link", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Sensor transfer events" },
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Sensor reclaimed from watch")
            .setContentText("The watch was unreachable, so this phone reconnected to the sensor.")
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }

    /**
     * A quiet notification (not an alarm) when no readings have arrived for [minutes]. Always offers
     * a Dismiss button; adds a "Reclaim sensor" button only when the watch currently holds the
     * sensor (so the phone can pull the connection back).
     */
    fun signalLoss(context: Context, minutes: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(SIGNAL_LOSS_CHANNEL, "Signal loss", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "No recent sensor readings" },
        )
        val watchNode = (tk.glucodata.WatchLink.transferState.value
            as? tk.glucodata.WatchLink.TransferState.WatchHasSensor)?.nodeId
        val text = if (watchNode != null) {
            "No new sensor data for $minutes min. The watch currently holds the sensor."
        } else {
            "No new sensor data for $minutes min."
        }
        val builder = NotificationCompat.Builder(context, SIGNAL_LOSS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("No recent readings")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .addAction(0, "Dismiss", actionIntent(context, NotificationActionReceiver.ACTION_DISMISS, null, 1))
        if (watchNode != null) {
            builder.addAction(0, "Reclaim sensor", actionIntent(context, NotificationActionReceiver.ACTION_RECLAIM, watchNode, 2))
        }
        runCatching { nm.notify(SIGNAL_LOSS_ID, builder.build()) }
    }

    fun cancelSignalLoss(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(SIGNAL_LOSS_ID) }
    }

    private fun actionIntent(context: Context, action: String, node: String?, req: Int): PendingIntent {
        val i = Intent(context, NotificationActionReceiver::class.java).setAction(action)
        if (node != null) i.putExtra(NotificationActionReceiver.EXTRA_NODE, node)
        return PendingIntent.getBroadcast(
            context, req, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
