package de.mogluco.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.mogluco.MoglucoApp
import de.mogluco.MainActivity

/**
 * Restores the keep-alive service after a reboot or an app update.
 *
 * Nothing else survives either event: without this receiver there is no BLE connection, no
 * readings and — critically — no low/high/signal-loss alarms until the user happens to open the
 * app. BOOT_COMPLETED is on the allowed background-FGS-start exemption list, and
 * [GlucoseNotificationService] is a specialUse FGS, so the start is permitted; we only attempt it
 * when the service would run anyway (persistent notification or any glucose alarm enabled — see
 * [GlucoseNotificationService.shouldRun]).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val prefs = (context.applicationContext as MoglucoApp).preferences
        if (!GlucoseNotificationService.shouldRun(prefs)) return

        // ForegroundServiceStartNotAllowedException (Android 12+) shouldn't happen here — boot is
        // an exemption — but OEM battery policies do deny it in the wild. Alarms are
        // safety-relevant, so never fail silently: tell the user to open the app instead.
        runCatching {
            ContextCompat.startForegroundService(
                context, Intent(context, GlucoseNotificationService::class.java),
            )
        }.onFailure { postOpenAppNotification(context) }
    }

    /** A plain high-priority notification: alarms are NOT running until Mogluco is opened. */
    private fun postOpenAppNotification(context: Context) {
        ensureChannel(context)
        val tap = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Glucose alarms not running")
            .setContentText("Open Mogluco to restart glucose monitoring and alarms.")
            .setContentIntent(tap)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Monitoring interrupted", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Warns when glucose monitoring couldn't restart after a reboot"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        mgr.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "boot_restart_v1"
        const val NOTIF_ID = 0xB001
    }
}
