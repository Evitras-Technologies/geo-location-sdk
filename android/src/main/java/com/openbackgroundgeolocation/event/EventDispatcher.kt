package com.openbackgroundgeolocation.event

import android.content.Context
import android.content.Intent
import com.facebook.react.ReactApplication
import com.facebook.react.bridge.ReactContext
import com.facebook.react.common.LifecycleState
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.openbackgroundgeolocation.config.ConfigStore
import com.openbackgroundgeolocation.headless.BackgroundGeolocationHeadlessService
import com.openbackgroundgeolocation.util.JsonConverter
import com.openbackgroundgeolocation.util.WakeLocks
import org.json.JSONArray
import org.json.JSONObject

/**
 * Routes SDK events to JavaScript.
 *
 * With the app in the foreground the event is emitted to JS listeners. With
 * `enableHeadless: true` it is delivered to the registered headless task
 * whenever the app is not in the foreground — terminated, or alive in the
 * background (see [shouldRunHeadless] for why the latter matters).
 */
object EventDispatcher {

    const val EVENT_LOCATION = "location"
    const val EVENT_MOTIONCHANGE = "motionchange"
    const val EVENT_ACTIVITYCHANGE = "activitychange"
    const val EVENT_PROVIDERCHANGE = "providerchange"
    const val EVENT_GEOFENCE = "geofence"
    const val EVENT_GEOFENCESCHANGE = "geofenceschange"
    const val EVENT_HEARTBEAT = "heartbeat"
    const val EVENT_HTTP = "http"
    const val EVENT_CONNECTIVITYCHANGE = "connectivitychange"
    const val EVENT_POWERSAVECHANGE = "powersavechange"
    const val EVENT_ENABLEDCHANGE = "enabledchange"
    const val EVENT_WATCHPOSITION = "watchposition"

    fun emit(context: Context, eventName: String, payload: JSONObject) {
        // Give the JS listener time to act on this before the CPU sleeps.
        WakeLocks.holdFor(context, WakeLocks.EVENT_MILLIS)
        // A dead/reloading React context or a malformed payload must never
        // crash the host app or abort native tracking.
        runCatching {
            val reactContext = currentReactContext(context)
            if (reactContext != null && !shouldRunHeadless(context, reactContext)) {
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit(eventName, JsonConverter.jsonToWritableMap(payload))
            } else {
                dispatchHeadless(context, eventName, payload.toString())
            }
        }
    }

    fun emit(context: Context, eventName: String, payload: Boolean) {
        runCatching {
            val reactContext = currentReactContext(context)
            if (reactContext != null && !shouldRunHeadless(context, reactContext)) {
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit(eventName, payload)
            } else {
                dispatchHeadless(context, eventName, JSONObject().put("value", payload).toString())
            }
        }
    }

    fun emit(context: Context, eventName: String, payload: JSONArray) {
        val reactContext = currentReactContext(context) ?: return
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, JsonConverter.jsonToWritableArray(payload))
    }

    /**
     * Whether an event should run as a headless task although a React context
     * is alive.
     *
     * With the app out of the foreground, React Native stops JS timers unless
     * a headless task is running — and `fetch` resolves through
     * `setTimeout(0)`. A listener's upload then completes on the wire but never
     * resolves in JS, silently stalling every upload that follows. As a
     * headless task the event keeps timers running for exactly as long as the
     * work takes. Only when the host opted in with `enableHeadless` and so has
     * a task registered; otherwise events reach listeners as before.
     */
    private fun shouldRunHeadless(context: Context, reactContext: ReactContext): Boolean =
        ConfigStore.get(context).enableHeadless &&
            reactContext.lifecycleState != LifecycleState.RESUMED

    /**
     * The live React context, from whichever runtime the host app uses.
     *
     * A non-null context is enough here: `hasActiveReactInstance()` is
     * annotated test-only, and every emit is already wrapped in runCatching,
     * so a torn-down instance degrades to the headless path rather than
     * throwing.
     */
    @Suppress("DEPRECATION")
    private fun currentReactContext(context: Context): ReactContext? =
        runCatching {
            val application = context.applicationContext as? ReactApplication ?: return null
            // New Architecture (bridgeless): the running instance belongs to
            // ReactHost. Reading `reactNativeHost.reactInstanceManager` there
            // builds a second, never-started legacy instance whose context is
            // always null — so every event fell through to the headless path
            // (or was dropped) even with the app open.
            application.reactHost?.currentReactContext?.let { return it }
            val host = application.reactNativeHost
            // Only read an instance that exists; creating one is the bug above.
            if (!host.hasInstance()) return null
            host.reactInstanceManager.currentReactContext
        }.getOrNull()

    private fun dispatchHeadless(context: Context, eventName: String, payloadJson: String) {
        if (!ConfigStore.get(context).enableHeadless) return
        runCatching {
            val intent = Intent(context, BackgroundGeolocationHeadlessService::class.java)
                .putExtra(BackgroundGeolocationHeadlessService.EXTRA_EVENT_NAME, eventName)
                .putExtra(BackgroundGeolocationHeadlessService.EXTRA_EVENT_PARAMS, payloadJson)
            context.startService(intent)
        }
    }
}
