package com.openbackgroundgeolocation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.openbackgroundgeolocation.engine.TrackingEngine
import com.openbackgroundgeolocation.geofence.PolygonGeometry
import com.openbackgroundgeolocation.util.JsonConverter
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The React Native bridge. Thin by design: every method delegates to the
 * process-lifetime [TrackingEngine] so behaviour is identical whether invoked
 * from the foreground app or a headless context.
 */
class BackgroundGeolocationModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), PermissionListener {

    private val engine: TrackingEngine get() = TrackingEngine.get(reactContext)
    private val backgroundTaskCounter = AtomicInteger(0)
    private var pendingPermissionResult: ((Int) -> Unit)? = null
    private var awaitingBackgroundPermission = false

    override fun getName(): String = MODULE_NAME

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @ReactMethod
    fun ready(config: ReadableMap, promise: Promise) {
        engine.config.merge(JsonConverter.readableMapToJson(config))
        engine.database.pruneLog(engine.config.logMaxDays)
        // Resume tracking if it was enabled when the app was last terminated —
        // prompting for permission first when it is missing (revoked by the
        // user, or a previous session never completed the grant).
        // `autoRequestPermissions` lets the SDK own the whole onboarding
        // sequence at launch so the host app implements nothing.
        if (engine.config.autoRequestPermissions) {
            requestAllPermissionsInternal {
                if (engine.config.enabled) engine.start(engine.config.trackingMode)
                resolveState(promise)
            }
            return
        }
        if (engine.config.enabled) {
            ensureLocationPermission {
                engine.start(engine.config.trackingMode)
                resolveState(promise)
            }
            return
        }
        resolveState(promise)
    }

    private fun requestAllPermissionsInternal(onComplete: () -> Unit) {
        launchPermissionRequest {
            ensureLocationServicesEnabled {
                promptBatteryOptimizationIfNeeded(onComplete)
            }
        }
    }

    @ReactMethod
    fun reset(config: ReadableMap, promise: Promise) {
        engine.config.reset(JsonConverter.readableMapToJson(config))
        resolveState(promise)
    }

    @ReactMethod
    fun setConfig(config: ReadableMap, promise: Promise) {
        engine.config.merge(JsonConverter.readableMapToJson(config))
        engine.onConfigChanged()
        resolveState(promise)
    }

    @ReactMethod
    fun getState(promise: Promise) = resolveState(promise)

    @ReactMethod
    fun start(promise: Promise) {
        ensureLocationPermission {
            engine.start(TrackingEngine.TRACKING_MODE_LOCATION)
            resolveState(promise)
        }
    }

    @ReactMethod
    fun stop(promise: Promise) {
        engine.stop()
        resolveState(promise)
    }

    @ReactMethod
    fun startGeofences(promise: Promise) {
        ensureLocationPermission {
            engine.start(TrackingEngine.TRACKING_MODE_GEOFENCE)
            resolveState(promise)
        }
    }

    /**
     * Request location permission when missing, then ensure device location
     * services (GPS) are actually switched on, then continue. Mirrors the
     * familiar plugin behaviour where `start()` prompts automatically. When the
     * user denies either step, tracking stays dormant — never a crash.
     */
    private fun ensureLocationPermission(onComplete: () -> Unit) {
        if (engine.hasLocationPermission()) {
            ensureLocationServicesEnabled(onComplete)
            return
        }
        launchPermissionRequest { ensureLocationServicesEnabled(onComplete) }
    }

    /**
     * Show the system "Turn on location?" dialog when device location services
     * are off — the same in-app popup Uber/Swiggy-style apps present, which
     * enables GPS without sending the user into Settings. Proceeds regardless
     * of the outcome so tracking still engages if the user declines.
     */
    private fun ensureLocationServicesEnabled(onComplete: () -> Unit) {
        val activity = reactContext.currentActivity
        if (activity == null) {
            onComplete()
            return
        }
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            engine.config.locationUpdateInterval
        ).build()
        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
            .build()

        LocationServices.getSettingsClient(activity)
            .checkLocationSettings(settingsRequest)
            .addOnSuccessListener { onComplete() } // GPS already on
            .addOnFailureListener { error ->
                if (error is ResolvableApiException) {
                    runCatching {
                        error.startResolutionForResult(activity, LOCATION_SETTINGS_REQUEST_CODE)
                    }
                }
                // Continue either way: the user may enable it from the dialog,
                // and tracking should engage as soon as a fix becomes available.
                onComplete()
            }
    }

    /**
     * Ask the user to switch on device location services. Resolves `true` when
     * they are already on or the user accepted the prompt.
     */
    @ReactMethod
    fun requestLocationServices(promise: Promise) {
        ensureLocationServicesEnabled {
            promise.resolve(engine.buildProviderState().optBoolean("enabled", false))
        }
    }

    @ReactMethod
    fun changePace(isMoving: Boolean, promise: Promise) {
        engine.changePace(isMoving)
        promise.resolve(null)
    }

    // ── Geolocation ───────────────────────────────────────────────────────────

    @ReactMethod
    fun getCurrentPosition(options: ReadableMap, promise: Promise) {
        // Auto-request permission first, matching start() — a one-shot position
        // is often the first location feature an app exercises.
        ensureLocationPermission {
            engine.getCurrentPosition(
                JsonConverter.readableMapToJson(options),
                onSuccess = { record -> promise.resolve(JsonConverter.jsonToWritableMap(record)) },
                onError = { code -> promise.reject(code.toString(), locationErrorMessage(code)) }
            )
        }
    }

    private fun locationErrorMessage(code: Int): String = when (code) {
        TrackingEngine.ERROR_PERMISSION_DENIED -> "Location permission denied"
        TrackingEngine.ERROR_POSITION_UNAVAILABLE -> "Location unavailable — is GPS turned on?"
        TrackingEngine.ERROR_TIMEOUT -> "Timed out waiting for a location fix"
        else -> "Location error: $code"
    }

    @ReactMethod
    fun watchPosition(options: ReadableMap, promise: Promise) {
        engine.watchPosition(JsonConverter.readableMapToJson(options))
        promise.resolve(null)
    }

    @ReactMethod
    fun stopWatchPosition(promise: Promise) {
        engine.stopWatchPosition()
        promise.resolve(null)
    }

    @ReactMethod
    fun getOdometer(promise: Promise) {
        promise.resolve(engine.config.odometer.toDouble())
    }

    @ReactMethod
    fun setOdometer(value: Double, promise: Promise) {
        engine.config.odometer = value.toFloat()
        ensureLocationPermission {
            engine.getCurrentPosition(
                JSONObject().put("persist", false),
                onSuccess = { record -> promise.resolve(JsonConverter.jsonToWritableMap(record)) },
                onError = { code -> promise.reject(code.toString(), locationErrorMessage(code)) }
            )
        }
    }

    // ── Database & HTTP ───────────────────────────────────────────────────────

    @ReactMethod
    fun getLocations(promise: Promise) {
        val locations = engine.database.allLocations(engine.config.locationsOrderDirection)
        val array = com.facebook.react.bridge.Arguments.createArray()
        locations.forEach { row -> array.pushMap(JsonConverter.jsonToWritableMap(row.json)) }
        promise.resolve(array)
    }

    /**
     * Connectivity and sync health: whether the device is online, how long the
     * current or last outage lasted, how many records are queued, and upload
     * latency. Lets an app show "offline — 42 locations queued" instead of
     * appearing frozen.
     */
    @ReactMethod
    fun getSyncStatus(promise: Promise) {
        val status = engine.uploader.networkMonitor.toJson(engine.database.locationCount())
        promise.resolve(JsonConverter.jsonToWritableMap(status))
    }

    @ReactMethod
    fun getCount(promise: Promise) {
        promise.resolve(engine.database.locationCount().toDouble())
    }

    @ReactMethod
    fun insertLocation(location: ReadableMap, promise: Promise) {
        val json = JsonConverter.readableMapToJson(location)
        val uuid = json.optString("uuid").ifEmpty { UUID.randomUUID().toString() }
        json.put("uuid", uuid)
        engine.database.insertLocation(uuid, System.currentTimeMillis(), json)
        promise.resolve(uuid)
    }

    @ReactMethod
    fun destroyLocations(promise: Promise) {
        engine.database.deleteAllLocations()
        promise.resolve(null)
    }

    @ReactMethod
    fun destroyLocation(uuid: String, promise: Promise) {
        if (engine.database.deleteLocationByUuid(uuid)) {
            promise.resolve(null)
        } else {
            promise.reject("404", "Location not found: $uuid")
        }
    }

    @ReactMethod
    fun sync(promise: Promise) {
        if (engine.config.url.isEmpty()) {
            promise.reject("500", "Cannot sync: no Config.url is set")
            return
        }
        // Explicit sync(): bypass backoff, the caller is deliberately retrying.
        engine.uploader.flush(force = true) { synced ->
            val array = com.facebook.react.bridge.Arguments.createArray()
            synced.forEach { record -> array.pushMap(JsonConverter.jsonToWritableMap(record)) }
            promise.resolve(array)
        }
    }

    // ── Geofencing ────────────────────────────────────────────────────────────

    @ReactMethod
    fun addGeofence(geofence: ReadableMap, promise: Promise) {
        val json = JsonConverter.readableMapToJson(geofence)
        val error = validateGeofence(json)
        if (error != null) {
            promise.reject("422", error)
            return
        }
        engine.geofenceManager.add(json)
        promise.resolve(null)
    }

    @ReactMethod
    fun addGeofences(geofences: ReadableArray, promise: Promise) {
        val array = JsonConverter.readableArrayToJson(geofences)
        for (index in 0 until array.length()) {
            val error = validateGeofence(array.getJSONObject(index))
            if (error != null) {
                promise.reject("422", "Geofence at index $index: $error")
                return
            }
        }
        engine.geofenceManager.addAll(array)
        promise.resolve(null)
    }

    /**
     * A geofence is valid as either a circle (latitude/longitude/radius) or a
     * polygon (three or more vertices). Returns an error message, or null.
     */
    private fun validateGeofence(json: JSONObject): String? {
        if (!json.has("identifier")) return "identifier is required"
        if (PolygonGeometry.isPolygon(json)) {
            return if (PolygonGeometry.parseVertices(json) != null) null
            else "vertices must be at least 3 [latitude, longitude] pairs"
        }
        if (!json.has("latitude") || !json.has("longitude") || !json.has("radius")) {
            return "requires latitude, longitude and radius (or 3+ vertices for a polygon)"
        }
        return null
    }

    @ReactMethod
    fun removeGeofence(identifier: String, promise: Promise) {
        engine.geofenceManager.remove(identifier)
        promise.resolve(null)
    }

    @ReactMethod
    fun removeGeofences(promise: Promise) {
        engine.geofenceManager.removeAll()
        promise.resolve(null)
    }

    @ReactMethod
    fun getGeofences(promise: Promise) {
        val array = com.facebook.react.bridge.Arguments.createArray()
        engine.geofenceManager.all().forEach { json ->
            array.pushMap(JsonConverter.jsonToWritableMap(json))
        }
        promise.resolve(array)
    }

    @ReactMethod
    fun getGeofence(identifier: String, promise: Promise) {
        val geofence = engine.geofenceManager.get(identifier)
        if (geofence != null) {
            promise.resolve(JsonConverter.jsonToWritableMap(geofence))
        } else {
            promise.reject("404", "Geofence not found: $identifier")
        }
    }

    @ReactMethod
    fun geofenceExists(identifier: String, promise: Promise) {
        promise.resolve(engine.geofenceManager.exists(identifier))
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    /**
     * Run the complete onboarding sequence in the order Android and Play
     * policy require, so host apps never have to orchestrate it:
     *
     *  1. Foreground location (+ activity recognition, notifications)
     *  2. Prominent-disclosure dialog, then background location
     *  3. System "Turn on location?" prompt when GPS is off
     *  4. Battery-optimization whitelist (essential on Samsung/Xiaomi/Oppo)
     *
     * Resolves with the resulting state of each item rather than rejecting —
     * partial grants are normal and tracking degrades gracefully.
     */
    @ReactMethod
    fun requestAllPermissions(promise: Promise) {
        requestAllPermissionsInternal {
            promise.resolve(JsonConverter.jsonToWritableMap(buildPermissionSummary()))
        }
    }

    /** Current state of every permission the SDK depends on. */
    @ReactMethod
    fun getPermissionStatus(promise: Promise) {
        promise.resolve(JsonConverter.jsonToWritableMap(buildPermissionSummary()))
    }

    private fun buildPermissionSummary(): JSONObject {
        val fineGranted = ContextCompat.checkSelfPermission(
            reactContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val backgroundGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                reactContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        val activityGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                reactContext, Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                reactContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        val providerState = engine.buildProviderState()
        return JSONObject()
            .put("status", currentAuthorizationStatus())
            .put("location", fineGranted)
            .put("backgroundLocation", backgroundGranted)
            .put("activityRecognition", activityGranted)
            .put("notifications", notificationsGranted)
            .put("locationServicesEnabled", providerState.optBoolean("enabled", false))
            .put("batteryOptimizationDisabled", isWhitelistedFromBatteryOptimization())
            // `true` only when nothing further is needed for reliable
            // background tracking on this device.
            .put(
                "readyForBackgroundTracking",
                fineGranted && backgroundGranted &&
                    providerState.optBoolean("enabled", false) &&
                    isWhitelistedFromBatteryOptimization()
            )
    }

    /**
     * Show the system battery-optimization exemption dialog when the app is
     * not yet whitelisted. Without it Samsung/Xiaomi/Oppo suspend background
     * tracking regardless of how the SDK is implemented.
     */
    private fun promptBatteryOptimizationIfNeeded(onComplete: () -> Unit) {
        if (isWhitelistedFromBatteryOptimization()) {
            onComplete()
            return
        }
        runCatching {
            // See the policy note in showSettings(): permitted because
            // uninterrupted background location is this SDK's core function.
            @Suppress("BatteryLife")
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${reactContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            reactContext.startActivity(intent)
        }
        // The dialog has no result callback; continue immediately and let the
        // caller re-read the status afterwards.
        onComplete()
    }

    @ReactMethod
    fun requestPermission(promise: Promise) {
        launchPermissionRequest { status ->
            if (status == TrackingEngine.AUTHORIZATION_DENIED) {
                promise.reject(status.toString(), "Location permission denied")
            } else {
                promise.resolve(status)
            }
        }
    }

    private fun launchPermissionRequest(onResult: (Int) -> Unit) {
        val activity = reactContext.currentActivity as? PermissionAwareActivity
        if (activity == null) {
            onResult(currentAuthorizationStatus())
            return
        }
        pendingPermissionResult = onResult
        awaitingBackgroundPermission = false

        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        activity.requestPermissions(permissions.toTypedArray(), PERMISSION_REQUEST_CODE, this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ): Boolean {
        if (requestCode != PERMISSION_REQUEST_CODE && requestCode != BACKGROUND_REQUEST_CODE) {
            return false
        }
        val onResult = pendingPermissionResult ?: return true

        val fineGranted = ContextCompat.checkSelfPermission(
            reactContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val wantsAlways = engine.config.locationAuthorizationRequest == "Always"
            // Already "Allow all the time": the disclosure and the Settings
            // trip would be asked again on every call for nothing.
            val backgroundGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    reactContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            if (fineGranted && wantsAlways && !backgroundGranted &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !awaitingBackgroundPermission
            ) {
                awaitingBackgroundPermission = true
                showBackgroundDisclosureThenRequest()
                return true
            }
        }

        finishPermissionFlow()
        return true
    }

    /**
     * Google Play "prominent disclosure" requirement: before requesting
     * background location, show an in-app dialog explaining that location is
     * collected even when the app is closed. Text is customizable via
     * `Config.backgroundPermissionRationale`; declining skips the background
     * request (foreground-only tracking still works).
     */
    private fun showBackgroundDisclosureThenRequest() {
        val activity = reactContext.currentActivity
        val permissionActivity = activity as? PermissionAwareActivity
        if (activity == null || permissionActivity == null) {
            finishPermissionFlow()
            return
        }
        val rationale = engine.config.backgroundPermissionRationale
        val title = rationale.optString("title", "Allow background location?")
        val message = rationale.optString(
            "message",
            "This app collects location data to enable tracking even when the app is closed or not in use."
        )
        val positiveLabel = rationale.optString("positiveAction", "Change to \"Allow all the time\"")
        val negativeLabel = rationale.optString("negativeAction", "Cancel")

        activity.runOnUiThread {
            android.app.AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(positiveLabel) { _, _ ->
                    permissionActivity.requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                        BACKGROUND_REQUEST_CODE,
                        this
                    )
                }
                .setNegativeButton(negativeLabel) { _, _ -> finishPermissionFlow() }
                .show()
        }
    }

    private fun finishPermissionFlow() {
        val onResult = pendingPermissionResult
        pendingPermissionResult = null
        awaitingBackgroundPermission = false
        val status = currentAuthorizationStatus()
        if (status == TrackingEngine.AUTHORIZATION_DENIED) {
            maybeShowSettingsRedirect()
        }
        onResult?.invoke(status)
    }

    /**
     * After two denials Android permanently silences the system permission
     * dialog — requestPermissions() then reports "denied" instantly with no
     * UI. Detect that state and show our own dialog routing the user to the
     * app's Settings page, so the user is *always* prompted one way or another.
     */
    private fun maybeShowSettingsRedirect() {
        val activity = reactContext.currentActivity ?: return
        val systemDialogStillAvailable = ActivityCompat.shouldShowRequestPermissionRationale(
            activity, Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (systemDialogStillAvailable) return // next request will show the system dialog

        activity.runOnUiThread {
            android.app.AlertDialog.Builder(activity)
                .setTitle("Location permission required")
                .setMessage(
                    "Location access is turned off for this app, and Android will no " +
                        "longer show the permission popup.\n\nPlease enable it manually:\n" +
                        "Permissions → Location → \"Allow all the time\""
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${reactContext.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { reactContext.startActivity(intent) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun currentAuthorizationStatus(): Int {
        val fineGranted = ContextCompat.checkSelfPermission(
            reactContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val backgroundGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                reactContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        return when {
            fineGranted && backgroundGranted -> TrackingEngine.AUTHORIZATION_ALWAYS
            fineGranted -> TrackingEngine.AUTHORIZATION_WHEN_IN_USE
            else -> TrackingEngine.AUTHORIZATION_DENIED
        }
    }

    @ReactMethod
    fun requestTemporaryFullAccuracy(purpose: String, promise: Promise) {
        // Android has no reduced-accuracy authorization concept; always full.
        promise.resolve(0)
    }

    @ReactMethod
    fun getProviderState(promise: Promise) {
        promise.resolve(JsonConverter.jsonToWritableMap(engine.buildProviderState()))
    }

    // ── Device state ──────────────────────────────────────────────────────────

    @ReactMethod
    fun isPowerSaveMode(promise: Promise) {
        val powerManager = reactContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        promise.resolve(powerManager.isPowerSaveMode)
    }

    @ReactMethod
    fun getSensors(promise: Promise) {
        val sensorManager = reactContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val result = JSONObject()
            .put("platform", "android")
            .put("accelerometer", sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null)
            .put("gyroscope", sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null)
            .put("magnetometer", sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null)
            .put(
                "significant_motion",
                sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) != null
            )
        promise.resolve(JsonConverter.jsonToWritableMap(result))
    }

    @ReactMethod
    fun getDeviceInfo(promise: Promise) {
        val result = JSONObject()
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("version", Build.VERSION.RELEASE)
            .put("platform", "android")
            .put("framework", "react-native")
            .put("deviceId", engine.config.deviceId)
        promise.resolve(JsonConverter.jsonToWritableMap(result))
    }

    @ReactMethod
    fun getDeviceId(promise: Promise) {
        promise.resolve(engine.config.deviceId)
    }

    // ── Background tasks ──────────────────────────────────────────────────────

    @ReactMethod
    fun startBackgroundTask(promise: Promise) {
        // The foreground service already keeps the process alive on Android;
        // issue an identifier for API parity with iOS.
        promise.resolve(backgroundTaskCounter.incrementAndGet())
    }

    @ReactMethod
    fun stopBackgroundTask(taskId: Int, promise: Promise) {
        promise.resolve(null)
    }

    // ── Debug & logging ───────────────────────────────────────────────────────

    @ReactMethod
    fun playSound(soundId: Int, promise: Promise) {
        runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(soundId, 300)
        }
        promise.resolve(null)
    }

    @ReactMethod
    fun log(level: String, message: String, promise: Promise) {
        engine.database.appendLog(level, message)
        promise.resolve(null)
    }

    @ReactMethod
    fun getLog(promise: Promise) {
        promise.resolve(engine.database.readLog())
    }

    @ReactMethod
    fun destroyLog(promise: Promise) {
        engine.database.destroyLog()
        promise.resolve(null)
    }

    @ReactMethod
    fun emailLog(email: String, promise: Promise) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, "BackgroundGeolocation log")
            putExtra(Intent.EXTRA_TEXT, engine.database.readLog())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { reactContext.startActivity(intent) }
            .onSuccess { promise.resolve(null) }
            .onFailure { promise.reject("500", "No email client available") }
    }

    // ── Device settings redirects ─────────────────────────────────────────────

    @ReactMethod
    fun showSettings(setting: String, promise: Promise) {
        val intent = when (setting) {
            "IGNORE_BATTERY_OPTIMIZATIONS" -> {
                if (isWhitelistedFromBatteryOptimization()) {
                    promise.resolve(null) // already whitelisted — nothing to ask
                    return
                }
                // Direct system "Allow?" dialog — far higher acceptance than
                // sending users hunting through the Settings list.
                //
                // Play policy permits this only for apps whose core function
                // genuinely breaks under Doze. Continuous background location
                // tracking is such a case (Google lists it explicitly), and the
                // SDK never forces it: the host app decides whether to ask.
                @Suppress("BatteryLife")
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${reactContext.packageName}")
                )
            }
            "POWER_MANAGER" -> resolvePowerManagerIntent()
            else -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${reactContext.packageName}")
            )
        }
        if (intent == null) {
            promise.reject("404", "No vendor power-manager screen on this device")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { reactContext.startActivity(intent) }
            .onSuccess { promise.resolve(null) }
            .onFailure { promise.reject("500", "Unable to open settings screen") }
    }

    @ReactMethod
    fun isIgnoringBatteryOptimizations(promise: Promise) {
        promise.resolve(isWhitelistedFromBatteryOptimization())
    }

    private fun isWhitelistedFromBatteryOptimization(): Boolean {
        val powerManager = reactContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(reactContext.packageName)
    }

    /**
     * Known OEM auto-start / power-manager screens. Ordered by Indian-market
     * share: Xiaomi/Redmi/Poco (MIUI), Vivo/iQOO, Oppo/Realme (ColorOS),
     * Samsung, Transsion (Tecno/Infinix/itel), OnePlus, Huawei/Honor, Asus,
     * Meizu. Multiple candidates per vendor — OEMs rename these across
     * OS versions.
     */
    private fun resolvePowerManagerIntent(): Intent? {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.securitycenter" to "com.miui.powercenter.PowerSettings",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.privacypermissionsentry.PermissionTopActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.transsion.phonemanager" to "com.itel.autobootmanager.activity.AutoBootMgrActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.asus.mobilemanager" to "com.asus.mobilemanager.powersaver.PowerSaverSettings",
            "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity"
        )
        val packageManager = reactContext.packageManager
        for ((packageName, className) in candidates) {
            val intent = Intent().setClassName(packageName, className)
            if (packageManager.resolveActivity(intent, 0) != null) return intent
        }
        return null
    }

    // ── NativeEventEmitter contract ───────────────────────────────────────────

    @ReactMethod
    fun addListener(eventName: String) = Unit

    @ReactMethod
    fun removeListeners(count: Int) = Unit

    private fun resolveState(promise: Promise) {
        promise.resolve(JsonConverter.jsonToWritableMap(engine.config.toStateJson()))
    }

    companion object {
        const val MODULE_NAME = "OpenBackgroundGeolocation"
        private const val PERMISSION_REQUEST_CODE = 9945
        private const val BACKGROUND_REQUEST_CODE = 9946
        private const val LOCATION_SETTINGS_REQUEST_CODE = 9949
    }
}
