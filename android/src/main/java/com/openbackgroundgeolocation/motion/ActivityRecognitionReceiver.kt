package com.openbackgroundgeolocation.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.openbackgroundgeolocation.engine.TrackingEngine

/**
 * Receives activity broadcasts and forwards them to the [TrackingEngine]
 * state machine. Transition events (exact moment an activity starts) take
 * priority over periodic samples.
 */
class ActivityRecognitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val engine = TrackingEngine.get(context)

        if (ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent) ?: return
            // A transition ENTER is a definitive signal — full confidence.
            result.transitionEvents.lastOrNull()?.let { event ->
                engine.onActivityDetected(mapActivityType(event.activityType), 100)
            }
            return
        }

        if (ActivityRecognitionResult.hasResult(intent)) {
            val result = ActivityRecognitionResult.extractResult(intent) ?: return
            val mostProbable = result.mostProbableActivity ?: return
            engine.onActivityDetected(
                mapActivityType(mostProbable.type),
                mostProbable.confidence
            )
        }
    }

    private fun mapActivityType(detectedType: Int): String = when (detectedType) {
        DetectedActivity.STILL -> "still"
        DetectedActivity.WALKING -> "walking"
        DetectedActivity.RUNNING -> "running"
        DetectedActivity.ON_FOOT -> "on_foot"
        DetectedActivity.ON_BICYCLE -> "on_bicycle"
        DetectedActivity.IN_VEHICLE -> "in_vehicle"
        else -> "unknown"
    }
}
