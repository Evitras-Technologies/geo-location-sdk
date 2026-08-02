package com.openbackgroundgeolocation.headless

import android.content.Intent
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.openbackgroundgeolocation.util.JsonConverter
import org.json.JSONObject

/**
 * Delivers SDK events to the JS task registered via
 * `BackgroundGeolocation.registerHeadlessTask()` while the app is terminated.
 *
 * The JS task key must match `HEADLESS_TASK_KEY` in the TypeScript layer.
 */
class BackgroundGeolocationHeadlessService : HeadlessJsTaskService() {

    override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig? {
        val eventName = intent?.getStringExtra(EXTRA_EVENT_NAME) ?: return null
        val paramsJson = intent.getStringExtra(EXTRA_EVENT_PARAMS) ?: "{}"

        val data = Arguments.createMap().apply {
            putString("name", eventName)
            putMap(
                "params",
                JsonConverter.jsonToWritableMap(
                    runCatching { JSONObject(paramsJson) }.getOrDefault(JSONObject())
                )
            )
        }

        return HeadlessJsTaskConfig(TASK_KEY, data, TASK_TIMEOUT_MS, true)
    }

    companion object {
        const val TASK_KEY = "OpenBackgroundGeolocationHeadlessTask"
        const val EXTRA_EVENT_NAME = "event_name"
        const val EXTRA_EVENT_PARAMS = "event_params"
        private const val TASK_TIMEOUT_MS = 60_000L
    }
}
