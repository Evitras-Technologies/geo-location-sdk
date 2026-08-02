package com.openbackgroundgeolocation.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openbackgroundgeolocation.config.ConfigStore
import com.openbackgroundgeolocation.engine.TrackingEngine

/**
 * Resumes tracking after a device reboot (`startOnBoot: true`) or an app
 * update (`stopOnTerminate: false`), without waiting for the user to open
 * the app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val config = ConfigStore.get(context)
        if (!config.enabled) return

        val isBoot = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        val isAppUpdate = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED

        val shouldResume =
            (isBoot && config.startOnBoot) || (isAppUpdate && !config.stopOnTerminate)
        if (!shouldResume) return

        if (isBoot) config.didDeviceReboot = true
        TrackingEngine.get(context).start(config.trackingMode)
    }
}
