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

    /**
     * Never redeliver. React Native's service asks for START_REDELIVER_INTENT,
     * so after the process is killed Android replays every event it had
     * handed over — minutes-old locations arriving in one burst as if new,
     * which a consumer reporting "where the truck is now" would file as
     * current. Events are momentary; what must survive a kill is persisted in
     * the SDK's own database.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

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
