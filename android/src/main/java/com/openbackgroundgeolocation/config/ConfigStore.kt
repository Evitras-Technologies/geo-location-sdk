package com.openbackgroundgeolocation.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persisted SDK configuration + runtime state flags.
 *
 * The merged config survives app restarts and device reboots so the tracking
 * service can restore itself headlessly. All access is process-local and
 * synchronized on the instance.
 */
class ConfigStore private constructor(context: Context) {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private var configJson: JSONObject = loadPersistedConfig()

    // ── Persistence ───────────────────────────────────────────────────────────

    @Synchronized
    fun merge(incoming: JSONObject) {
        incoming.keys().forEach { key -> configJson.put(key, incoming.get(key)) }
        sanitize()
        persist()
    }

    /** Delegates to [ConfigValidator]; see there for the rules. */
    private fun sanitize() {
        ConfigValidator.sanitize(configJson)
    }

    /** Discard all persisted values, then apply `incoming` over factory defaults. */
    @Synchronized
    fun reset(incoming: JSONObject) {
        configJson = JSONObject()
        merge(incoming)
    }

    @Synchronized
    fun toJson(): JSONObject = JSONObject(configJson.toString())

    private fun loadPersistedConfig(): JSONObject =
        runCatching { JSONObject(preferences.getString(KEY_CONFIG, null) ?: "{}") }
            .getOrDefault(JSONObject())

    private fun persist() {
        preferences.edit().putString(KEY_CONFIG, configJson.toString()).apply()
    }

    // ── Runtime state flags (separate from user config) ───────────────────────

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 1 = location + geofences, 0 = geofences-only. */
    var trackingMode: Int
        get() = preferences.getInt(KEY_TRACKING_MODE, 1)
        set(value) = preferences.edit().putInt(KEY_TRACKING_MODE, value).apply()

    var isMoving: Boolean
        get() = preferences.getBoolean(KEY_IS_MOVING, false)
        set(value) = preferences.edit().putBoolean(KEY_IS_MOVING, value).apply()

    var odometer: Float
        get() = preferences.getFloat(KEY_ODOMETER, 0f)
        set(value) = preferences.edit().putFloat(KEY_ODOMETER, value).apply()

    var didDeviceReboot: Boolean
        get() = preferences.getBoolean(KEY_DID_REBOOT, false)
        set(value) = preferences.edit().putBoolean(KEY_DID_REBOOT, value).apply()

    /**
     * Stable per-installation identifier, generated once and persisted. Every
     * HTTP upload carries it so a shared server can attribute records to the
     * device that produced them without the host app implementing anything.
     * Reset when the app is uninstalled — it identifies an installation, not a
     * person, and is deliberately not derived from hardware IDs (Play policy
     * forbids persistent hardware identifiers for this purpose).
     */
    @get:Synchronized
    val deviceId: String
        get() = preferences.getString(KEY_DEVICE_ID, null) ?: run {
            val generated = UUID.randomUUID().toString()
            preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
            generated
        }

    /** Human-readable device description sent alongside {@link deviceId}. */
    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"

    /** Device descriptor merged into every HTTP upload body. */
    fun deviceJson(): JSONObject = JSONObject()
        .put("id", deviceId)
        .put("name", deviceName)
        .put("model", Build.MODEL)
        .put("manufacturer", Build.MANUFACTURER)
        .put("platform", "android")
        .put("os_version", Build.VERSION.RELEASE)

    // ── Typed getters with factory defaults ───────────────────────────────────

    val desiredAccuracy: Int get() = configJson.optInt("desiredAccuracy", -1)
    val distanceFilter: Float get() = configJson.optDouble("distanceFilter", 10.0).toFloat()
    val stationaryRadius: Float
        get() = configJson.optDouble("stationaryRadius", 25.0).toFloat().coerceAtLeast(25f)
    val disableElasticity: Boolean get() = configJson.optBoolean("disableElasticity", false)
    val elasticityMultiplier: Float
        get() = configJson.optDouble("elasticityMultiplier", 1.0).toFloat()
    val stopAfterElapsedMinutes: Int get() = configJson.optInt("stopAfterElapsedMinutes", 0)
    val desiredOdometerAccuracy: Float
        get() = configJson.optDouble("desiredOdometerAccuracy", 100.0).toFloat()
    val locationUpdateInterval: Long get() = configJson.optLong("locationUpdateInterval", 1000L)
    val fastestLocationUpdateInterval: Long
        get() = configJson.optLong("fastestLocationUpdateInterval", 10000L)
    val allowIdenticalLocations: Boolean
        get() = configJson.optBoolean("allowIdenticalLocations", false)
    /**
     * Reject fixes implying a speed above this, in m/s.
     *
     * Default 55 m/s (≈200 km/h) — covers every road vehicle and most trains.
     * The previous default of 300 m/s was near the speed of sound and let
     * obvious GPS teleports through: a phone falling back to network
     * positioning can jump 400 m between fixes seconds apart, which registered
     * as ~290 km/h of "travel" and inflated the odometer. Raise it for
     * aviation use.
     */
    val speedJumpFilter: Float get() = configJson.optDouble("speedJumpFilter", 55.0).toFloat()

    /**
     * Discard fixes whose reported accuracy is worse than this many metres.
     * `0` (default) keeps every fix. Typical value 25–50 to drop cold-start
     * and indoor noise before it reaches your server.
     */
    val minimumAccuracy: Float get() = configJson.optDouble("minimumAccuracy", 0.0).toFloat()

    val isMovingOnStart: Boolean get() = configJson.optBoolean("isMoving", false)
    val stopTimeoutMinutes: Int get() = configJson.optInt("stopTimeout", 5)
    val activityRecognitionInterval: Long
        get() = configJson.optLong("activityRecognitionInterval", 10000L)
    val minimumActivityRecognitionConfidence: Int
        get() = configJson.optInt("minimumActivityRecognitionConfidence", 70)
    val disableStopDetection: Boolean get() = configJson.optBoolean("disableStopDetection", false)
    val motionTriggerDelayMinutes: Int get() = configJson.optInt("motionTriggerDelay", 0)

    val url: String get() = configJson.optString("url", "")
    val httpMethod: String get() = configJson.optString("method", "POST")
    val httpRootProperty: String get() = configJson.optString("httpRootProperty", "location")
    val params: JSONObject get() = configJson.optJSONObject("params") ?: JSONObject()
    val headers: JSONObject get() = configJson.optJSONObject("headers") ?: JSONObject()

    /**
     * The shape of one uploaded record: a JSON object with `<%= name %>` tags,
     * each replaced by that value of the record (`latitude`, `longitude`,
     * `accuracy`, `speed`, `heading`, `altitude`, `timestamp`, `uuid`,
     * `event`, `is_moving`, `odometer`, or a dotted path such as
     * `battery.level`). Text is not quoted for you: write
     * `"at":"<%= timestamp %>"`. With a template the request body is that
     * object and `params`, and nothing else is added to it.
     */
    val locationTemplate: String get() = configJson.optString("locationTemplate", "")

    /**
     * JWT (or bearer) authorization with automatic refresh:
     * `{ strategy, accessToken, refreshToken, refreshUrl, refreshPayload,
     *    refreshHeaders, expires }`. When the server answers 401 the SDK posts
     * to `refreshUrl`, stores the new token and retries the upload.
     */
    val authorization: JSONObject?
        get() = configJson.optJSONObject("authorization")

    /** Persist a refreshed access token so it survives restarts. */
    @Synchronized
    fun updateAccessToken(accessToken: String, refreshToken: String?, expiresAtSeconds: Long?) {
        val existing = configJson.optJSONObject("authorization") ?: return
        existing.put("accessToken", accessToken)
        refreshToken?.let { existing.put("refreshToken", it) }
        expiresAtSeconds?.let { existing.put("expires", it) }
        configJson.put("authorization", existing)
        persist()
    }
    val extras: JSONObject get() = configJson.optJSONObject("extras") ?: JSONObject()
    val autoSync: Boolean get() = configJson.optBoolean("autoSync", true)
    val autoSyncThreshold: Int get() = configJson.optInt("autoSyncThreshold", 0)

    /**
     * Least time between two automatic uploads, in milliseconds; `0` (default)
     * uploads every record as it is written. For a server that wants a
     * position no more often than this while fixes are taken more often: the
     * upload that goes then carries the newest fix, not one as old as the
     * interval. A change of motion is uploaded regardless.
     */
    val autoSyncInterval: Long get() = configJson.optLong("autoSyncInterval", 0L)
    val batchSync: Boolean get() = configJson.optBoolean("batchSync", false)
    val maxBatchSize: Int get() = configJson.optInt("maxBatchSize", -1)
    val maxDaysToPersist: Int get() = configJson.optInt("maxDaysToPersist", 14)
    val maxRecordsToPersist: Int get() = configJson.optInt("maxRecordsToPersist", -1)
    val persistMode: Int get() = configJson.optInt("persistMode", PERSIST_MODE_ALL)
    val httpTimeout: Long get() = configJson.optLong("httpTimeout", 60000L)
    val locationsOrderDirection: String
        get() = configJson.optString("locationsOrderDirection", "ASC")

    val stopOnTerminate: Boolean get() = configJson.optBoolean("stopOnTerminate", true)
    val startOnBoot: Boolean get() = configJson.optBoolean("startOnBoot", false)
    val heartbeatIntervalSeconds: Int get() = configJson.optInt("heartbeatInterval", 0)

    /**
     * Seconds between fresh location fixes recorded while the device is
     * *stationary*. `0` (default) keeps the battery-optimal behaviour of no
     * updates at all while still. Set e.g. `12` to keep a server-side map
     * alive with a periodic "still here" position.
     */
    val stationaryUpdateIntervalSeconds: Int
        get() = configJson.optInt("stationaryUpdateInterval", 0)
    val enableHeadless: Boolean get() = configJson.optBoolean("enableHeadless", false)
    val notification: JSONObject get() = configJson.optJSONObject("notification") ?: JSONObject()
    val backgroundPermissionRationale: JSONObject
        get() = configJson.optJSONObject("backgroundPermissionRationale") ?: JSONObject()
    val locationAuthorizationRequest: String
        get() = configJson.optString("locationAuthorizationRequest", "Always")

    /**
     * When `true`, `ready()` runs the full permission onboarding sequence
     * (location → background → GPS → battery whitelist) so the host app does
     * not orchestrate any of it. Default `false` to keep control with apps
     * that prefer to prompt at their own moment.
     */
    val autoRequestPermissions: Boolean
        get() = configJson.optBoolean("autoRequestPermissions", false)

    val geofenceProximityRadius: Float
        get() = configJson.optDouble("geofenceProximityRadius", 1000.0).toFloat()
    val geofenceInitialTriggerEntry: Boolean
        get() = configJson.optBoolean("geofenceInitialTriggerEntry", true)
    val geofenceModeHighAccuracy: Boolean
        get() = configJson.optBoolean("geofenceModeHighAccuracy", false)

    val debug: Boolean get() = configJson.optBoolean("debug", false)
    val logLevel: Int get() = configJson.optInt("logLevel", 0)
    val logMaxDays: Int get() = configJson.optInt("logMaxDays", 3)

    val schedule: JSONArray get() = configJson.optJSONArray("schedule") ?: JSONArray()

    /** The full state document returned to JS: config + live flags. */
    fun toStateJson(): JSONObject = toJson().apply {
        put("enabled", enabled)
        put("trackingMode", trackingMode)
        put("isMoving", isMoving)
        put("odometer", odometer.toDouble())
        put("schedulerEnabled", false)
        put("didDeviceReboot", didDeviceReboot)
    }

    companion object {
        const val PERSIST_MODE_ALL = 2
        const val PERSIST_MODE_LOCATION = 1
        const val PERSIST_MODE_GEOFENCE = -1
        const val PERSIST_MODE_NONE = 0

        private const val PREFERENCES_NAME = "open_bg_geo_config"
        private const val KEY_CONFIG = "config_json"
        private const val KEY_ENABLED = "state_enabled"
        private const val KEY_TRACKING_MODE = "state_tracking_mode"
        private const val KEY_IS_MOVING = "state_is_moving"
        private const val KEY_ODOMETER = "state_odometer"
        private const val KEY_DID_REBOOT = "state_did_reboot"
        private const val KEY_DEVICE_ID = "state_device_id"

        @Volatile
        private var instance: ConfigStore? = null

        fun get(context: Context): ConfigStore =
            instance ?: synchronized(this) {
                instance ?: ConfigStore(context.applicationContext).also { instance = it }
            }
    }
}
