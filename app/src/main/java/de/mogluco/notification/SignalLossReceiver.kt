package de.mogluco.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

/**
 * The Doze-proof half of [AlarmController]'s signal-loss watchdog. Its coroutine waits with a
 * `delay`, which stands still while the CPU is suspended — and once readings stop, nothing else
 * wakes an idle, unplugged phone. So the same deadline is armed here with AlarmManager; when it
 * fires this just runs the controller's check.
 */
class SignalLossReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // AlarmManager's wake lock ends when this returns; bridge the moment until the ringing
        // service (started from the check) holds its own.
        runCatching {
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "juggluco:loss-check")
                .acquire(5_000L)
        }
        AlarmController.onLossAlarm()
    }

    companion object {
        private const val TAG = "SignalLossReceiver"

        /** Arm the check for [atMillis] (wall clock), replacing any earlier time; 0 cancels it. */
        fun schedule(context: Context, atMillis: Long) {
            runCatching {
                val alarms = context.getSystemService(AlarmManager::class.java)
                val operation = PendingIntent.getBroadcast(
                    context, 0, Intent(context, SignalLossReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                if (atMillis <= 0L) {
                    alarms.cancel(operation)
                    return
                }
                // Exact where the system allows it without a further permission: always before
                // Android 12, and from 12 on while the app is exempt from battery optimisation.
                // Otherwise the inexact variant — it still fires in Doze, but the system may
                // deliver it some minutes late.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
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
