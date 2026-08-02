package com.openbackgroundgeolocation.config

import org.json.JSONObject

/**
 * Clamps user-supplied configuration into ranges the tracking engine can
 * operate in.
 *
 * An SDK cannot trust its callers: a negative `distanceFilter` or a zero
 * `locationUpdateInterval` would spin location services and destroy the
 * battery, and out-of-range values make platform APIs throw. Kept as a pure
 * function of a JSON document so it is directly unit-testable.
 */
object ConfigValidator {

    fun sanitize(config: JSONObject): JSONObject {
        clampDouble(config, "distanceFilter", minimum = 0.0, maximum = 100_000.0)
        clampDouble(config, "stationaryRadius", minimum = 25.0, maximum = 100_000.0)
        clampDouble(config, "elasticityMultiplier", minimum = 0.0, maximum = 100.0)
        clampDouble(config, "desiredOdometerAccuracy", minimum = 0.0, maximum = 100_000.0)
        clampDouble(config, "speedJumpFilter", minimum = 1.0, maximum = 10_000.0)
        clampDouble(config, "minimumAccuracy", minimum = 0.0, maximum = 10_000.0)
        clampDouble(config, "geofenceProximityRadius", minimum = 100.0, maximum = 1_000_000.0)

        clampInt(config, "stopTimeout", minimum = 0, maximum = 10_080) // <= 7 days
        clampInt(config, "stopAfterElapsedMinutes", minimum = 0, maximum = 10_080)
        clampInt(config, "motionTriggerDelay", minimum = 0, maximum = 1_440)
        clampInt(config, "minimumActivityRecognitionConfidence", minimum = 0, maximum = 100)
        clampInt(config, "maxDaysToPersist", minimum = -1, maximum = 3_650)
        clampInt(config, "autoSyncThreshold", minimum = 0, maximum = 100_000)

        clampLong(config, "locationUpdateInterval", minimum = 1_000L, maximum = 3_600_000L)
        clampLong(config, "fastestLocationUpdateInterval", minimum = 0L, maximum = 3_600_000L)
        clampLong(config, "activityRecognitionInterval", minimum = 1_000L, maximum = 3_600_000L)
        clampLong(config, "httpTimeout", minimum = 1_000L, maximum = 600_000L)

        // heartbeatInterval: 0 disables it; otherwise the platform floor is 60s.
        val heartbeat = config.optInt("heartbeatInterval", 0)
        if (heartbeat != 0 && heartbeat < 60) config.put("heartbeatInterval", 60)

        // stationaryUpdateInterval: 0 disables it; a 5s floor keeps GPS
        // wake-ups sane — anything faster should use continuous tracking.
        val stationaryInterval = config.optInt("stationaryUpdateInterval", 0)
        if (stationaryInterval != 0) {
            config.put("stationaryUpdateInterval", stationaryInterval.coerceIn(5, 3_600))
        }

        val method = config.optString("method", "POST").uppercase()
        if (method !in VALID_HTTP_METHODS) config.put("method", "POST")

        if (config.optString("httpRootProperty", "location").isEmpty()) {
            config.put("httpRootProperty", "location")
        }
        return config
    }

    private fun clampDouble(config: JSONObject, key: String, minimum: Double, maximum: Double) {
        if (!config.has(key)) return
        val value = config.optDouble(key, minimum)
        config.put(key, if (value.isNaN()) minimum else value.coerceIn(minimum, maximum))
    }

    private fun clampInt(config: JSONObject, key: String, minimum: Int, maximum: Int) {
        if (!config.has(key)) return
        config.put(key, config.optInt(key, minimum).coerceIn(minimum, maximum))
    }

    private fun clampLong(config: JSONObject, key: String, minimum: Long, maximum: Long) {
        if (!config.has(key)) return
        config.put(key, config.optLong(key, minimum).coerceIn(minimum, maximum))
    }

    private val VALID_HTTP_METHODS = setOf("POST", "PUT", "OPTIONS")
}
