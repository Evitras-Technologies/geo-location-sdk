package com.openbackgroundgeolocation.event

import android.content.Context
import android.content.Intent
import com.facebook.react.ReactApplication
import com.facebook.react.bridge.ReactContext
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.openbackgroundgeolocation.config.ConfigStore
import com.openbackgroundgeolocation.headless.BackgroundGeolocationHeadlessService
import com.openbackgroundgeolocation.util.JsonConverter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Routes SDK events to JavaScript.
 *
 * When a React context is alive the event is emitted over the bridge; when the
 * app has been terminated and `enableHeadless: true`, the event is delivered
 * to the registered headless task instead.
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
        // A dead/reloading React context or a malformed payload must never
        // crash the host app or abort native tracking.
        runCatching {
            val reactContext = currentReactContext(context)
            if (reactContext != null) {
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
            if (reactContext != null) {
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

    private fun currentReactContext(context: Context): ReactContext? =
        runCatching {
            val application = context.applicationContext as? ReactApplication ?: return null
            // A non-null context is enough here: `hasActiveReactInstance()` is
            // annotated test-only, and every emit is already wrapped in
            // runCatching, so a torn-down instance degrades to the headless
            // path rather than throwing.
            application.reactNativeHost.reactInstanceManager.currentReactContext
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
