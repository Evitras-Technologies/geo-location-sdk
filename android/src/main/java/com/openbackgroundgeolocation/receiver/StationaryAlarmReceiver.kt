package com.openbackgroundgeolocation.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openbackgroundgeolocation.config.ConfigStore
import com.openbackgroundgeolocation.engine.TrackingEngine
import java.util.concurrent.TimeUnit

/**
 * Drives periodic location updates while the device is stationary.
 *
 * Uses AlarmManager rather than a `Handler` because handler-based timers are
 * suspended once the device enters Doze (screen off, stationary) — exactly the
 * situation these updates exist to cover. `setAndAllowWhileIdle` keeps firing
 * in Doze, though Android rate-limits it to roughly once every 9 minutes
 * unless the app is whitelisted from battery optimization — which is why the
 * SDK also prompts for that whitelist.
 */
class StationaryAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val config = ConfigStore.get(context)
        if (!config.enabled || config.isMoving) return
        if (config.stationaryUpdateIntervalSeconds <= 0) return

        val pendingResult = goAsync()
        TrackingEngine.get(context).recordStationaryUpdate {
            schedule(context) // chain the next one
            pendingResult.finish()
        }
    }

    companion object {
        private const val REQUEST_CODE = 9950

        fun schedule(context: Context) {
            val config = ConfigStore.get(context)
            val intervalSeconds = config.stationaryUpdateIntervalSeconds
            if (intervalSeconds <= 0) return

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = System.currentTimeMillis() +
                TimeUnit.SECONDS.toMillis(intervalSeconds.toLong())

            // Deliberately *inexact*: exact alarms require SCHEDULE_EXACT_ALARM,
            // which Play restricts to alarm-clock/calendar apps — using it for
            // location tracking would be a policy violation. setAndAllowWhileIdle
            // still fires during Doze; Android may shift it by a few seconds when
            // batching wake-ups, which is fine for periodic position reports and
            // is better for battery anyway.
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context)
                )
            }
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            runCatching { alarmManager.cancel(pendingIntent(context)) }
        }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, StationaryAlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
