package com.openbackgroundgeolocation.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.openbackgroundgeolocation.engine.TrackingEngine

/**
 * Receives geofence transition broadcasts from Play Services — including
 * while the app is terminated — and forwards them to the [TrackingEngine].
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return
        if (geofencingEvent.hasError()) return

        val action = when (geofencingEvent.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
            else -> return
        }

        val engine = TrackingEngine.get(context)
        val triggeringLocation = geofencingEvent.triggeringLocation

        geofencingEvent.triggeringGeofences?.forEach { geofence ->
            if (geofence.requestId == GeofenceManager.STATIONARY_IDENTIFIER) {
                if (action == "EXIT") engine.onStationaryGeofenceExit()
            } else {
                engine.onGeofenceTransition(geofence.requestId, action, triggeringLocation)
            }
        }
    }
}
