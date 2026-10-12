package com.openbackgroundgeolocation.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openbackgroundgeolocation.config.ConfigStore
import com.openbackgroundgeolocation.engine.TrackingEngine
import com.openbackgroundgeolocation.util.WakeLocks

/**
 * Foreground service which keeps the process alive while tracking. All actual
 * tracking logic lives in [TrackingEngine]; this service only owns the
 * persistent notification demanded by Android for background location.
 */
class LocationTrackingService : Service() {

    // CPU wakefulness is no longer held here for the service's whole life:
    // see WakeLocks for the bounded holds that replaced it.

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14 throws SecurityException when a location-type foreground
        // service starts without location permission — never start unpermitted.
        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        TrackingEngine.get(this).onTrackingServiceReady()
        return START_STICKY
    }

    override fun onDestroy() {
        WakeLocks.release()
        super.onDestroy()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onTaskRemoved(rootIntent: Intent?) {
        val config = ConfigStore.get(this)
        if (config.stopOnTerminate) {
            TrackingEngine.get(this).stop()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(): Notification {
        val config = ConfigStore.get(this)
        val settings = config.notification

        val importance =
            channelImportanceFor(settings.optInt("priority", NotificationCompat.PRIORITY_LOW))
        // Channels are immutable, so the default id carries its importance:
        // changing `notification.priority` then creates a new channel instead
        // of silently keeping the old behaviour. A caller-supplied channelId is
        // used verbatim — they own its lifecycle.
        val channelId = settings.optString("channelId", "")
            .ifEmpty { "${DEFAULT_CHANNEL_ID}_$importance" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Notification channels are immutable once created, so a channel
            // made by an earlier build keeps whatever sound it was created
            // with. Drop the legacy channel so the silent settings below
            // actually apply on upgrade.
            LEGACY_CHANNEL_IDS
                .filter { it != channelId }
                .forEach { legacyId ->
                    runCatching { notificationManager.deleteNotificationChannel(legacyId) }
                }

            val channel = NotificationChannel(
                channelId,
                settings.optString("channelName", "Location Service"),
                importance
            ).apply {
                // IMPORTANCE_LOW alone is silent on stock Android, but MIUI and
                // some other skins still play the default tone unless sound,
                // vibration and lights are switched off explicitly.
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val appName = applicationInfo.loadLabel(packageManager).toString()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(settings.optString("title", appName))
            .setContentText(settings.optString("text", "Location service activated"))
            .setSmallIcon(resolveSmallIcon(settings.optString("smallIcon", "")))
            .setOngoing(settings.optBoolean("sticky", true))
            .setPriority(settings.optInt("priority", NotificationCompat.PRIORITY_LOW))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Belt and braces for pre-Oreo devices and skins that honour
            // builder flags over channel settings.
            .setSilent(true)
            .setDefaults(0)
            .setSound(null)
            .setVibrate(null)
            .setOnlyAlertOnce(true)

        settings.optString("color", "").takeIf { it.isNotEmpty() }?.let { colorString ->
            runCatching { builder.setColor(Color.parseColor(colorString)) }
        }
        contentIntent?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    /**
     * Map the configured notification priority onto a channel importance.
     *
     * `MIN` is the quietest setting Android permits for a foreground service:
     * the notification stays in the shade but the persistent **status-bar icon
     * is hidden** on most devices. The notification itself can never be removed
     * — Android requires a visible notification for any foreground service, so
     * users always know background location is active.
     */
    private fun channelImportanceFor(priority: Int): Int = when {
        priority <= NotificationCompat.PRIORITY_MIN -> NotificationManager.IMPORTANCE_MIN
        priority >= NotificationCompat.PRIORITY_HIGH -> NotificationManager.IMPORTANCE_DEFAULT
        else -> NotificationManager.IMPORTANCE_LOW
    }

    /** Resolve `"drawable/my_icon"` / `"mipmap/ic_launcher"` names; falls back to the app icon. */
    private fun resolveSmallIcon(resourceName: String): Int {
        if (resourceName.isNotEmpty()) {
            val parts = resourceName.split("/")
            val (type, name) =
                if (parts.size == 2) parts[0] to parts[1] else "drawable" to parts[0]
            val resourceId = resources.getIdentifier(name, type, packageName)
            if (resourceId != 0) return resourceId
        }
        return applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_mylocation
    }

    companion object {
        private const val NOTIFICATION_ID = 9942
        /** Base id; the effective channel is "<base>_<importance>". */
        private const val DEFAULT_CHANNEL_ID = "open_bg_geo_silent"
        private val LEGACY_CHANNEL_IDS = listOf("open_bg_geo", "open_bg_geo_silent")

        fun start(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationTrackingService::class.java))
        }
    }
}
