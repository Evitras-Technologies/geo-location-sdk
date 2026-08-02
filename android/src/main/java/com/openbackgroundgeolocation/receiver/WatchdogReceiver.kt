package com.openbackgroundgeolocation.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openbackgroundgeolocation.config.ConfigStore
import com.openbackgroundgeolocation.engine.TrackingEngine
import com.openbackgroundgeolocation.service.LocationTrackingService
import java.util.concurrent.TimeUnit

/**
 * OEM-kill watchdog. Aggressive vendor battery managers (MIUI, ColorOS,
 * FuntouchOS, OneUI, …) are known to kill even foreground services. This
 * alarm fires periodically while tracking is enabled and restarts the
 * tracking service if it was killed, then re-arms itself.
 *
 * On Android 12+ a background service start can still be refused unless the
 * app is whitelisted from battery optimization — which the SDK asks users to
 * grant via `DeviceSettings.showIgnoreBatteryOptimizations()` — so failures
 * are swallowed and retried on the next tick.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val config = ConfigStore.get(context)
        if (!config.enabled || config.trackingMode != TrackingEngine.TRACKING_MODE_LOCATION) {
            return
        }
        val engine = TrackingEngine.get(context)
        if (engine.hasLocationPermission()) {
            runCatching { LocationTrackingService.start(context) }
        }
        // Safety-net sync: catches a missed connectivity callback, and records
        // stranded below `autoSyncThreshold` once the device stopped moving.
        runCatching { engine.flushPendingQueue() }
        schedule(context)
    }

    companion object {
        private const val REQUEST_CODE = 9948
        private val INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(15)

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + INTERVAL_MILLIS,
                pendingIntent(context)
            )
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
