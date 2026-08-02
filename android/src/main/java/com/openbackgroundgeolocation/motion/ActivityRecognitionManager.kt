package com.openbackgroundgeolocation.motion

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/**
 * Subscribes to OS activity signals which drive the SDK's motion-detection
 * state machine, using two complementary APIs:
 *
 *  - **Activity Transition API** — fires the instant the device enters
 *    still / walking / in_vehicle …; highly reliable in the background and
 *    extremely battery-cheap. This is the primary moving/stationary trigger.
 *  - **Sampled activity updates** — periodic classifications with confidence,
 *    powering `onActivityChange` events and acting as a fallback signal.
 */
class ActivityRecognitionManager(private val context: Context) {

    private val client = ActivityRecognition.getClient(context)
    private var started = false

    // Distinct PendingIntents per subscription — sharing one can cause the
    // second registration to silently supersede the first.
    private val sampledUpdatesIntent: PendingIntent by lazy {
        broadcastIntent(SAMPLED_REQUEST_CODE)
    }
    private val transitionUpdatesIntent: PendingIntent by lazy {
        broadcastIntent(TRANSITION_REQUEST_CODE)
    }

    private fun broadcastIntent(requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ActivityRecognitionReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

    @SuppressLint("MissingPermission")
    fun start(intervalMillis: Long) {
        // Each registration wrapped separately: if one API is unavailable on a
        // device, the other still engages.
        runCatching {
            client.requestActivityTransitionUpdates(buildTransitionRequest(), transitionUpdatesIntent)
            started = true
        }
        runCatching {
            client.requestActivityUpdates(intervalMillis, sampledUpdatesIntent)
            started = true
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!started) return
        runCatching { client.removeActivityUpdates(sampledUpdatesIntent) }
        runCatching { client.removeActivityTransitionUpdates(transitionUpdatesIntent) }
        started = false
    }

    private fun buildTransitionRequest(): ActivityTransitionRequest {
        val trackedActivities = listOf(
            DetectedActivity.STILL,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_FOOT,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE
        )
        val transitions = trackedActivities.map { activityType ->
            ActivityTransition.Builder()
                .setActivityType(activityType)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        }
        return ActivityTransitionRequest(transitions)
    }

    companion object {
        private const val SAMPLED_REQUEST_CODE = 9944
        private const val TRANSITION_REQUEST_CODE = 9947
    }
}
