package com.openbackgroundgeolocation.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.openbackgroundgeolocation.config.ConfigStore
import com.openbackgroundgeolocation.data.DatabaseHelper
import com.openbackgroundgeolocation.event.EventDispatcher
import com.openbackgroundgeolocation.geofence.GeofenceManager
import com.openbackgroundgeolocation.http.HttpUploader
import com.openbackgroundgeolocation.motion.ActivityRecognitionManager
import com.openbackgroundgeolocation.receiver.StationaryAlarmReceiver
import com.openbackgroundgeolocation.receiver.WatchdogReceiver
import com.openbackgroundgeolocation.service.LocationTrackingService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The core tracking state machine, shared by the React module, the foreground
 * service and every broadcast receiver. Lives for the process lifetime so
 * tracking continues while the React context is gone (headless mode).
 *
 * Motion model:
 *  - *stationary*: location updates off; a stationary geofence + activity
 *    recognition watch for movement.
 *  - *moving*: continuous location updates filtered by `distanceFilter`;
 *    after `stopTimeout` minutes of detected stillness the SDK returns to
 *    *stationary*.
 */
class TrackingEngine private constructor(private val context: Context) {

    val config: ConfigStore = ConfigStore.get(context)
    val database: DatabaseHelper = DatabaseHelper.get(context)
    val uploader: HttpUploader = HttpUploader(context)
    val geofenceManager: GeofenceManager = GeofenceManager(context)

    private val activityRecognition = ActivityRecognitionManager(context)
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var significantMotionListener: TriggerEventListener? = null
    private var stationaryAnchor: Location? = null
    private var passiveDriftCallback: LocationCallback? = null
    private var cachedBattery: JSONObject? = null
    private var cachedBatteryAtMillis = 0L
    private var lastPruneAtMillis = 0L
    private var lastRecordedLocation: Location? = null
    private var lastRecordedJson: JSONObject? = null
    // "unknown"/0 until activity recognition actually reports something.
    // Seeding these with "still"/100 would fabricate a confident reading —
    // and would hide the case where activity recognition never fires at all
    // (permission denied, Play Services unavailable), which consumers need to
    // be able to detect.
    private var lastActivityType: String = "unknown"
    private var lastActivityConfidence: Int = 0
    private var currentSpeedBand: Int = 0
    private var trackingCallbackActive = false
    private var systemReceiversRegistered = false

    private val stopTimeoutRunnable = Runnable { setMoving(false) }
    private val motionTriggerRunnable = Runnable { setMoving(true) }
    private val stopAfterElapsedRunnable = Runnable { stop() }
    /**
     * Record one fresh fix while stationary — persisted, uploaded and emitted
     * as a normal location so servers keep receiving "still here" positions
     * instead of silence. Driven by [StationaryAlarmReceiver] (AlarmManager),
     * which unlike a Handler keeps firing while the device is in Doze.
     */
    fun recordStationaryUpdate(onFinished: () -> Unit) {
        if (!config.enabled || config.isMoving) {
            onFinished()
            return
        }
        fetchSingleFix(timeoutMillis = 20_000) { location ->
            runCatching {
                if (location != null && config.enabled && !config.isMoving) {
                    accumulateOdometer(location)
                    lastRecordedLocation = location
                    val record = buildLocationRecord(location, event = "heartbeat")
                    lastRecordedJson = record
                    persistRecord(record, isGeofenceEvent = false)
                    EventDispatcher.emit(context, EventDispatcher.EVENT_LOCATION, record)
                    autoSyncIfDue()
                }
            }.onFailure { error -> logError("Stationary update failed", error) }
            onFinished()
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            lastRecordedJson?.let { location ->
                EventDispatcher.emit(
                    context,
                    EventDispatcher.EVENT_HEARTBEAT,
                    JSONObject().put("location", location)
                )
            }
            mainHandler.postDelayed(this, heartbeatMillis())
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Synchronized
    fun start(trackingMode: Int) {
        // The user can decline location permission, and nothing works without
        // it. Refuse to start rather than reporting success: previously the SDK
        // set `enabled = true` and emitted `enabledchange: true` regardless, so
        // an app would show "tracking on" while nothing was ever recorded, with
        // no way to discover why.
        if (!hasLocationPermission()) {
            config.enabled = false
            database.appendLog(
                "warn",
                "start() refused: location permission has not been granted. " +
                    "Call requestPermission() or requestAllPermissions(), then start() again."
            )
            registerSystemReceivers() // so a later grant surfaces via providerchange
            EventDispatcher.emit(
                context, EventDispatcher.EVENT_PROVIDERCHANGE, buildProviderState()
            )
            EventDispatcher.emit(context, EventDispatcher.EVENT_ENABLEDCHANGE, false)
            return
        }

        config.trackingMode = trackingMode
        config.enabled = true
        registerSystemReceivers()

        if (trackingMode == TRACKING_MODE_LOCATION) {
            LocationTrackingService.start(context)
            WatchdogReceiver.schedule(context)
            activityRecognition.start(config.activityRecognitionInterval)
            if (config.stopAfterElapsedMinutes > 0) {
                mainHandler.postDelayed(
                    stopAfterElapsedRunnable,
                    TimeUnit.MINUTES.toMillis(config.stopAfterElapsedMinutes.toLong())
                )
            }
        }
        geofenceManager.startMonitoring()

        // Drain anything left from a previous session. Without this a queue
        // built up while offline — then the app killed — would sit untouched
        // until the next location happened to be recorded, which may be a long
        // time if the device is stationary or tracking is geofence-only.
        uploader.flush(force = true)

        EventDispatcher.emit(context, EventDispatcher.EVENT_ENABLEDCHANGE, true)
        debugBeep(ToneGenerator.TONE_PROP_ACK)
    }

    /** Called by the foreground service once its notification is showing. */
    fun onTrackingServiceReady() {
        // With stop-detection disabled the SDK tracks continuously (ride-hailing
        // style): engage the moving state immediately so the user never has to
        // signal movement manually.
        val shouldEngageImmediately =
            config.disableStopDetection || config.isMovingOnStart || config.isMoving
        // `isInitial` only when tracking isn't already engaged — makes repeated
        // onStartCommand deliveries (watchdog restarts, service resurrection)
        // idempotent instead of re-emitting motionchange.
        setMoving(shouldEngageImmediately, isInitial = !trackingCallbackActive)
    }

    @Synchronized
    fun stop() {
        config.enabled = false
        config.isMoving = false
        mainHandler.removeCallbacks(stopTimeoutRunnable)
        mainHandler.removeCallbacks(motionTriggerRunnable)
        mainHandler.removeCallbacks(stopAfterElapsedRunnable)
        mainHandler.removeCallbacks(heartbeatRunnable)
        StationaryAlarmReceiver.cancel(context)
        WatchdogReceiver.cancel(context)
        stopContinuousUpdates()
        disarmSignificantMotionSensor()
        stopPassiveDriftWatch()
        activityRecognition.stop()
        geofenceManager.stopMonitoring()
        LocationTrackingService.stop(context)
        EventDispatcher.emit(context, EventDispatcher.EVENT_ENABLEDCHANGE, false)
        debugBeep(ToneGenerator.TONE_PROP_NACK)
    }

    fun changePace(isMoving: Boolean) {
        if (!config.enabled) return
        setMoving(isMoving)
    }

    /** Re-apply live-tunable settings (distanceFilter, accuracy, …) after `setConfig`. */
    @Synchronized
    fun onConfigChanged() {
        if (!config.enabled) return
        if (config.trackingMode != TRACKING_MODE_LOCATION) return

        if (config.isMoving) {
            startContinuousUpdates()
            return
        }
        // Currently stationary: pick up a newly configured (or cleared)
        // stationary-update interval without waiting for a state change.
        StationaryAlarmReceiver.cancel(context)
        StationaryAlarmReceiver.schedule(context)
        // Continuous mode may have just been enabled — engage immediately.
        if (config.disableStopDetection) setMoving(true)
    }

    // ── Motion state machine ──────────────────────────────────────────────────

    @Synchronized
    private fun setMoving(moving: Boolean, isInitial: Boolean = false) {
        if (!config.enabled || config.trackingMode != TRACKING_MODE_LOCATION) return
        // Continuous mode never powers tracking down.
        if (config.disableStopDetection && !moving) return
        if (!isInitial && config.isMoving == moving) return
        config.isMoving = moving
        mainHandler.removeCallbacks(stopTimeoutRunnable)
        mainHandler.removeCallbacks(motionTriggerRunnable)

        if (moving) {
            mainHandler.removeCallbacks(heartbeatRunnable)
            StationaryAlarmReceiver.cancel(context)
            geofenceManager.removeStationaryGeofence()
            disarmSignificantMotionSensor()
            stopPassiveDriftWatch()
            // Resurrect the foreground service if an OEM killed the process —
            // geofence / activity-recognition broadcasts are explicitly exempt
            // from Android 12+ background service-start restrictions.
            if (hasLocationPermission()) {
                runCatching { LocationTrackingService.start(context) }
            }
            startContinuousUpdates()
        } else {
            stopContinuousUpdates()
            // Redundant wake signals — activity recognition alone is throttled
            // on many OEMs (the classic "stuck in stationary" failure).
            armSignificantMotionSensor()
            startPassiveDriftWatch()
            if (heartbeatMillis() > 0) {
                mainHandler.postDelayed(heartbeatRunnable, heartbeatMillis())
            }
            StationaryAlarmReceiver.schedule(context)
        }

        // Record the transition with a fresh one-shot fix.
        fetchSingleFix(timeoutMillis = 30_000) { location ->
            if (location != null) {
                if (!moving) {
                    stationaryAnchor = location
                    geofenceManager.registerStationaryGeofence(
                        location.latitude,
                        location.longitude,
                        config.stationaryRadius
                    )
                }
                val record = buildLocationRecord(location, event = "motionchange")
                persistRecord(record, isGeofenceEvent = false)
                EventDispatcher.emit(
                    context,
                    EventDispatcher.EVENT_MOTIONCHANGE,
                    JSONObject().put("isMoving", moving).put("location", record)
                )
                EventDispatcher.emit(context, EventDispatcher.EVENT_LOCATION, record)
                autoSyncIfDue()
            }
        }
        debugBeep(if (moving) ToneGenerator.TONE_CDMA_HIGH_L else ToneGenerator.TONE_CDMA_LOW_L)
    }

    /** Movement signal from the stationary-geofence exit. */
    fun onStationaryGeofenceExit() {
        if (config.enabled && !config.isMoving) setMoving(true)
    }

    /**
     * Wake signal #2: the hardware significant-motion sensor. Fires once on
     * substantial device movement, requires **no permission**, works on
     * virtually every device (API 18+), and is immune to the OS throttling
     * that delays activity-recognition callbacks on Samsung/Xiaomi/etc.
     * A false trigger self-corrects: stop-detection returns to stationary
     * after `stopTimeout`.
     */
    private fun armSignificantMotionSensor() {
        if (significantMotionListener != null) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        val listener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                significantMotionListener = null // trigger sensors are one-shot
                if (config.enabled && !config.isMoving) setMoving(true)
            }
        }
        significantMotionListener = listener
        runCatching { sensorManager.requestTriggerSensor(listener, sensor) }
    }

    private fun disarmSignificantMotionSensor() {
        significantMotionListener?.let { listener ->
            sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)?.let { sensor ->
                runCatching { sensorManager.cancelTriggerSensor(listener, sensor) }
            }
        }
        significantMotionListener = null
    }

    /**
     * Wake signal #3: a passive location watch while stationary. Costs almost
     * nothing (piggybacks on fixes other apps request) and promotes to
     * *moving* when the device drifts beyond the stationary anchor.
     */
    @SuppressLint("MissingPermission")
    private fun startPassiveDriftWatch() {
        if (!hasLocationPermission() || passiveDriftCallback != null) return
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val fix = result.lastLocation ?: return
                val anchor = stationaryAnchor ?: return
                val driftThreshold = config.stationaryRadius.coerceAtLeast(fix.accuracy)
                if (config.enabled && !config.isMoving && fix.distanceTo(anchor) > driftThreshold) {
                    setMoving(true)
                }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 30_000L).build()
        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        passiveDriftCallback = callback
    }

    private fun stopPassiveDriftWatch() {
        passiveDriftCallback?.let { fusedClient.removeLocationUpdates(it) }
        passiveDriftCallback = null
    }

    /** Activity-recognition sample from the OS. */
    fun onActivityDetected(activityType: String, confidence: Int) {
        val changed = activityType != lastActivityType
        lastActivityType = activityType
        lastActivityConfidence = confidence
        if (changed) {
            EventDispatcher.emit(
                context,
                EventDispatcher.EVENT_ACTIVITYCHANGE,
                JSONObject().put("activity", activityType).put("confidence", confidence)
            )
        }
        if (!config.enabled || config.trackingMode != TRACKING_MODE_LOCATION) return
        if (config.disableStopDetection) return
        if (confidence < config.minimumActivityRecognitionConfidence) return

        val indicatesMovement = activityType != "still" && activityType != "unknown"
        if (indicatesMovement && !config.isMoving) {
            val delayMillis = TimeUnit.MINUTES.toMillis(config.motionTriggerDelayMinutes.toLong())
            mainHandler.removeCallbacks(motionTriggerRunnable)
            if (delayMillis > 0) {
                mainHandler.postDelayed(motionTriggerRunnable, delayMillis)
            } else {
                setMoving(true)
            }
        } else if (activityType == "still" && config.isMoving) {
            mainHandler.removeCallbacks(stopTimeoutRunnable)
            mainHandler.postDelayed(
                stopTimeoutRunnable,
                TimeUnit.MINUTES.toMillis(config.stopTimeoutMinutes.toLong())
            )
        } else if (indicatesMovement && config.isMoving) {
            mainHandler.removeCallbacks(stopTimeoutRunnable)
        }
    }

    // ── Continuous location updates ───────────────────────────────────────────

    private val trackingCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // An exception here would propagate into Play Services' callback
            // and crash the host app — never let that happen.
            runCatching { result.lastLocation?.let { onRawLocation(it) } }
                .onFailure { error -> logError("Failed to process location", error) }
        }
    }

    /** Record an internal failure to the SDK log without disturbing the app. */
    fun logError(message: String, error: Throwable? = null) {
        runCatching {
            database.appendLog("error", "$message: ${error?.message ?: "unknown"}")
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startContinuousUpdates() {
        if (!hasLocationPermission()) return
        stopContinuousUpdates()
        val fastestInterval =
            minOf(config.locationUpdateInterval, config.fastestLocationUpdateInterval)
        val request = LocationRequest.Builder(mapPriority(), config.locationUpdateInterval)
            .setMinUpdateIntervalMillis(fastestInterval)
            .setMinUpdateDistanceMeters(effectiveDistanceFilter())
            // Suppress inaccurate cold-start fixes and demand full-precision
            // coordinates — measurable accuracy win on the first points.
            .setWaitForAccurateLocation(true)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .build()
        fusedClient.requestLocationUpdates(request, trackingCallback, Looper.getMainLooper())
        trackingCallbackActive = true
    }

    @Synchronized
    private fun stopContinuousUpdates() {
        if (trackingCallbackActive) {
            fusedClient.removeLocationUpdates(trackingCallback)
            trackingCallbackActive = false
        }
    }

    private fun onRawLocation(location: Location) {
        if (!passesFilters(location)) return
        accumulateOdometer(location)
        maybeAdjustElasticity(location)

        lastRecordedLocation = location
        val record = buildLocationRecord(location, event = null)
        lastRecordedJson = record
        persistRecord(record, isGeofenceEvent = false)
        EventDispatcher.emit(context, EventDispatcher.EVENT_LOCATION, record)
        geofenceManager.evaluateProximity(location)
        evaluatePolygonGeofences(location)
        autoSyncIfDue()
        // Deliberately no beep per location: in realtime mode that is one tone
        // every second, which is unusable as a debug aid and sounds like a
        // malfunction. Debug tones mark life-cycle events only — start, stop,
        // motionchange and geofence transitions.
    }

    private fun passesFilters(location: Location): Boolean {
        val previous = lastRecordedLocation
        val rejection = LocationFilters.evaluate(
            accuracyMeters = location.accuracy,
            minimumAccuracyMeters = config.minimumAccuracy,
            isDuplicateCoordinate = previous != null &&
                previous.latitude == location.latitude &&
                previous.longitude == location.longitude,
            allowIdenticalLocations = config.allowIdenticalLocations,
            distanceFilterMeters = config.distanceFilter,
            metersFromPrevious = previous?.distanceTo(location) ?: 0f,
            secondsSincePrevious =
                if (previous != null) (location.time - previous.time) / 1000.0 else 0.0,
            speedJumpFilterMetersPerSecond = config.speedJumpFilter
        )
        return rejection == LocationFilters.Rejection.NONE
    }

    private fun accumulateOdometer(location: Location) {
        val previous = lastRecordedLocation ?: return
        val distance = previous.distanceTo(location)
        if (LocationFilters.contributesToOdometer(
                accuracyMeters = location.accuracy,
                desiredOdometerAccuracyMeters = config.desiredOdometerAccuracy,
                distanceMeters = distance,
                isMoving = config.isMoving,
                previousAccuracyMeters = previous.accuracy
            )
        ) {
            config.odometer += distance
        }
    }

    /**
     * Elasticity: scale the native distance filter with speed so highway
     * driving records fewer points. Re-requests updates only when the speed
     * band (5 m/s buckets) changes.
     */
    private fun maybeAdjustElasticity(location: Location) {
        if (config.disableElasticity || !location.hasSpeed()) return
        val band = LocationFilters.speedBand(location.speed)
        if (band != currentSpeedBand) {
            currentSpeedBand = band
            if (trackingCallbackActive) startContinuousUpdates()
        }
    }

    private fun effectiveDistanceFilter(): Float = LocationFilters.effectiveDistanceFilter(
        distanceFilterMeters = config.distanceFilter,
        speedBand = currentSpeedBand,
        elasticityMultiplier = config.elasticityMultiplier,
        disableElasticity = config.disableElasticity
    )

    // ── One-shot fixes ────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun fetchSingleFix(timeoutMillis: Long, callback: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            callback(null)
            return
        }
        val cancellation = CancellationTokenSource()
        mainHandler.postDelayed({ cancellation.cancel() }, timeoutMillis)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(timeoutMillis)
            .build()
        fusedClient.getCurrentLocation(request, cancellation.token)
            .addOnSuccessListener { callback(it) }
            .addOnFailureListener { callback(null) }
    }

    fun getCurrentPosition(options: JSONObject, onSuccess: (JSONObject) -> Unit, onError: (Int) -> Unit) {
        if (!hasLocationPermission()) {
            onError(ERROR_PERMISSION_DENIED)
            return
        }
        val timeout = options.optLong("timeout", 30_000L)
        val persist = options.optBoolean("persist", true)
        val extras = options.optJSONObject("extras")
        val maximumAge = options.optLong("maximumAge", 0L)
        val desiredAccuracy = options.optDouble("desiredAccuracy", 0.0).toFloat()
        val samples = options.optInt("samples", 3).coerceIn(1, 10)

        // Serve the last fix from cache when the caller allows it.
        val cached = lastRecordedLocation
        if (maximumAge > 0 && cached != null &&
            System.currentTimeMillis() - cached.time <= maximumAge
        ) {
            deliverCurrentPosition(cached, persist, extras, onSuccess)
            return
        }

        collectSamples(timeout, samples, desiredAccuracy) { bestFix ->
            if (bestFix == null) {
                onError(ERROR_TIMEOUT)
            } else {
                deliverCurrentPosition(bestFix, persist, extras, onSuccess)
            }
        }
    }

    /**
     * Sample the GPS up to `maxSamples` times (1s apart), resolving early when
     * a fix meets `desiredAccuracy`, and return the most accurate fix seen.
     * Multiple samples let the GPS chip settle, which markedly improves
     * one-shot readings over returning the first (often cold-start) fix.
     */
    @SuppressLint("MissingPermission")
    private fun collectSamples(
        timeoutMillis: Long,
        maxSamples: Int,
        desiredAccuracy: Float,
        onComplete: (Location?) -> Unit
    ) {
        var bestFix: Location? = null
        var sampleCount = 0
        var finished = false
        lateinit var samplingCallback: LocationCallback

        val finish = {
            if (!finished) {
                finished = true
                fusedClient.removeLocationUpdates(samplingCallback)
                onComplete(bestFix)
            }
        }

        samplingCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val fix = result.lastLocation ?: return
                sampleCount += 1
                if (bestFix == null || fix.accuracy < bestFix!!.accuracy) {
                    bestFix = fix
                }
                val accuracyMet = desiredAccuracy > 0 && fix.accuracy <= desiredAccuracy
                if (accuracyMet || sampleCount >= maxSamples) finish()
            }
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        fusedClient.requestLocationUpdates(request, samplingCallback, Looper.getMainLooper())
        mainHandler.postDelayed({ finish() }, timeoutMillis)
    }

    private fun deliverCurrentPosition(
        location: Location,
        persist: Boolean,
        extras: JSONObject?,
        onSuccess: (JSONObject) -> Unit
    ) {
        accumulateOdometer(location)
        lastRecordedLocation = location
        val record = buildLocationRecord(location, event = null, requestExtras = extras)
        lastRecordedJson = record
        if (persist) {
            persistRecord(record, isGeofenceEvent = false)
            EventDispatcher.emit(context, EventDispatcher.EVENT_LOCATION, record)
            autoSyncIfDue()
        }
        onSuccess(record)
    }

    // ── watchPosition ─────────────────────────────────────────────────────────

    private var watchCallback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun watchPosition(options: JSONObject) {
        if (!hasLocationPermission()) return
        stopWatchPosition()
        val interval = options.optLong("interval", 1000L)
        val persist = options.optBoolean("persist", false)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val record = buildLocationRecord(location, event = null)
                    if (persist) persistRecord(record, isGeofenceEvent = false)
                    EventDispatcher.emit(context, EventDispatcher.EVENT_WATCHPOSITION, record)
                }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval).build()
        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        watchCallback = callback
    }

    fun stopWatchPosition() {
        watchCallback?.let { fusedClient.removeLocationUpdates(it) }
        watchCallback = null
    }

    // ── Geofence transitions (from GeofenceBroadcastReceiver) ─────────────────

    fun onGeofenceTransition(identifier: String, action: String, location: Location?) {
        // Polygon geofences are registered with the OS as their bounding
        // circle, so a native ENTER only means "near". Confirm real containment
        // before reporting; EXIT from the bounding circle is always a true exit.
        if (geofenceManager.isPolygon(identifier) && location != null) {
            val isInside = geofenceManager.polygonContains(identifier, location)
            if (action == "ENTER" && !isInside) return // inside the circle, outside the shape
        }
        val geofenceJson = database.geofence(identifier)
        val geofenceDetail = JSONObject()
            .put("identifier", identifier)
            .put("action", action)
            .apply { geofenceJson?.optJSONObject("extras")?.let { put("extras", it) } }

        val record = location?.let {
            buildLocationRecord(it, event = "geofence").put("geofence", geofenceDetail)
        } ?: (lastRecordedJson ?: JSONObject())

        persistRecord(record, isGeofenceEvent = true)
        EventDispatcher.emit(
            context,
            EventDispatcher.EVENT_GEOFENCE,
            JSONObject()
                .put("identifier", identifier)
                .put("action", action)
                .put("location", record)
                .apply { geofenceJson?.optJSONObject("extras")?.let { put("extras", it) } }
        )
        autoSyncIfDue()
        debugBeep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD)
    }

    /**
     * Exact polygon transitions, evaluated on each recorded location. The OS
     * only wakes us for the bounding circle, so this is what gives polygon
     * geofences their true shape.
     */
    private fun evaluatePolygonGeofences(location: Location) {
        for ((identifier, isInside) in geofenceManager.evaluatePolygons(location)) {
            onGeofenceTransition(identifier, if (isInside) "ENTER" else "EXIT", location)
        }
    }

    // ── Record building & persistence ─────────────────────────────────────────

    fun buildLocationRecord(
        location: Location,
        event: String?,
        requestExtras: JSONObject? = null
    ): JSONObject {
        val coords = JSONObject()
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("accuracy", location.accuracy.toDouble())
            .put("speed", if (location.hasSpeed()) location.speed.toDouble() else -1.0)
            .put("heading", if (location.hasBearing()) location.bearing.toDouble() else -1.0)
            .put("altitude", if (location.hasAltitude()) location.altitude else -1.0)
            .put("ellipsoidal_altitude", if (location.hasAltitude()) location.altitude else -1.0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            coords.put(
                "speed_accuracy",
                if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else -1.0
            )
            coords.put(
                "heading_accuracy",
                if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees.toDouble() else -1.0
            )
            coords.put(
                "altitude_accuracy",
                if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else -1.0
            )
        } else {
            coords.put("speed_accuracy", -1.0)
            coords.put("heading_accuracy", -1.0)
            coords.put("altitude_accuracy", -1.0)
        }

        val mergedExtras = JSONObject()
        config.extras.let { configured ->
            configured.keys().forEach { key -> mergedExtras.put(key, configured.get(key)) }
        }
        requestExtras?.keys()?.forEach { key -> mergedExtras.put(key, requestExtras.get(key)) }

        val isMock =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock
            else @Suppress("DEPRECATION") location.isFromMockProvider

        return JSONObject().apply {
            put("timestamp", isoTimestamp(location.time))
            put("age", System.currentTimeMillis() - location.time)
            event?.let { put("event", it) }
            put("is_moving", config.isMoving)
            put("uuid", UUID.randomUUID().toString())
            put("coords", coords)
            put(
                "activity",
                JSONObject().put("type", lastActivityType).put("confidence", lastActivityConfidence)
            )
            put("battery", batterySnapshot())
            put("odometer", config.odometer.toDouble())
            put("mock", isMock)
            if (mergedExtras.length() > 0) put("extras", mergedExtras)
        }
    }

    fun persistRecord(record: JSONObject, isGeofenceEvent: Boolean) {
        runCatching { persistRecordInternal(record, isGeofenceEvent) }
            .onFailure { error -> logError("Failed to persist location", error) }
    }

    private fun persistRecordInternal(record: JSONObject, isGeofenceEvent: Boolean) {
        val mode = config.persistMode
        val shouldPersist = when {
            mode == ConfigStore.PERSIST_MODE_NONE -> false
            mode == ConfigStore.PERSIST_MODE_LOCATION && isGeofenceEvent -> false
            mode == ConfigStore.PERSIST_MODE_GEOFENCE && !isGeofenceEvent -> false
            else -> true
        }
        if (!shouldPersist) return
        database.insertLocation(
            record.optString("uuid", UUID.randomUUID().toString()),
            System.currentTimeMillis(),
            record
        )
        // Pruning is a DELETE with a subquery; running it on every insert was
        // pure overhead. Retention is a coarse policy — periodic is enough.
        val now = System.currentTimeMillis()
        if (now - lastPruneAtMillis >= PRUNE_INTERVAL_MILLIS) {
            lastPruneAtMillis = now
            database.pruneLocations(config.maxDaysToPersist, config.maxRecordsToPersist)
        }
    }

    private fun autoSyncIfDue() {
        if (uploader.shouldAutoSync()) uploader.flush()
    }

    // ── Provider / connectivity / power monitoring ────────────────────────────

    fun buildProviderState(): JSONObject {
        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled =
            runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }
                .getOrDefault(false)
        val networkEnabled =
            runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
                .getOrDefault(false)

        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val backgroundGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val status = when {
            fineGranted && backgroundGranted -> AUTHORIZATION_ALWAYS
            fineGranted -> AUTHORIZATION_WHEN_IN_USE
            else -> AUTHORIZATION_DENIED
        }

        return JSONObject()
            .put("enabled", fineGranted && (gpsEnabled || networkEnabled))
            .put("status", status)
            .put("network", networkEnabled)
            .put("gps", gpsEnabled)
            .put("accuracyAuthorization", ACCURACY_AUTHORIZATION_FULL)
    }

    private val providerChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            EventDispatcher.emit(context, EventDispatcher.EVENT_PROVIDERCHANGE, buildProviderState())
        }
    }

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            EventDispatcher.emit(
                context, EventDispatcher.EVENT_POWERSAVECHANGE, powerManager.isPowerSaveMode
            )
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val offlineDurationMillis = uploader.networkMonitor.onConnected()
            EventDispatcher.emit(
                context, EventDispatcher.EVENT_CONNECTIVITYCHANGE,
                JSONObject()
                    .put("connected", true)
                    .put("offlineDurationMs", offlineDurationMillis ?: 0L)
                    .put("queuedRecords", database.locationCount())
            )
            offlineDurationMillis?.let { duration ->
                database.appendLog(
                    "info",
                    "Network restored after ${duration / 1000}s offline; " +
                        "${database.locationCount()} record(s) queued — syncing now"
                )
            }
            // force = true is essential: failures during the outage will have
            // pushed the retry backoff out to minutes, and without bypassing it
            // the queue would sit idle long after the network returned. This was
            // the "network is back but nothing updates" bug.
            uploader.flush(force = true)
        }

        override fun onLost(network: Network) {
            uploader.networkMonitor.onDisconnected()
            EventDispatcher.emit(
                context, EventDispatcher.EVENT_CONNECTIVITYCHANGE,
                JSONObject()
                    .put("connected", false)
                    .put("offlineDurationMs", 0L)
                    .put("queuedRecords", database.locationCount())
            )
            database.appendLog("warn", "Network lost — queueing locations to SQLite")
        }
    }

    @Synchronized
    private fun registerSystemReceivers() {
        if (systemReceiversRegistered) return
        systemReceiversRegistered = true
        ContextCompat.registerReceiver(
            context,
            providerChangeReceiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(
            context,
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        // registerDefaultNetworkCallback is API 24+; on API 23 fall back to a
        // request-based callback so connectivity monitoring still works
        // instead of throwing NoSuchMethodError.
        runCatching {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, networkCallback)
            }
            // Seed from the real state instead of assuming: if the device is
            // already offline when tracking starts, no `onLost` will fire and
            // the monitor would wrongly believe it is connected.
            syncConnectivityFromSystem(connectivityManager)
        }
    }

    /**
     * Reconcile the monitor with the OS's actual connectivity.
     *
     * `ConnectivityManager` callbacks can be missed — registered after the
     * transition, dropped in Doze, or never delivered for the initial state.
     * Since the uploader skips work while it believes it is offline, a stale
     * `false` would stall the queue indefinitely. This is the corrective read.
     */
    fun syncConnectivityFromSystem(
        connectivityManager: ConnectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    ) {
        val isOnline = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val capabilities = connectivityManager.getNetworkCapabilities(
                    connectivityManager.activeNetwork
                )
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } else {
                @Suppress("DEPRECATION")
                connectivityManager.activeNetworkInfo?.isConnected == true
            }
        }.getOrDefault(true) // assume online rather than stall the queue

        if (isOnline) {
            uploader.networkMonitor.onConnected()
        } else {
            uploader.networkMonitor.onDisconnected()
        }
    }

    /**
     * Safety-net sync, driven by the watchdog alarm.
     *
     * Covers the cases the event-driven triggers miss: a connectivity callback
     * that never arrived, and records sitting below `autoSyncThreshold` after
     * the device stopped producing new locations.
     */
    fun flushPendingQueue() {
        if (!config.enabled || config.url.isEmpty()) return
        syncConnectivityFromSystem()
        if (!uploader.networkMonitor.isConnected) return
        if (database.locationCount() > 0) uploader.flush(force = true)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun mapPriority(): Int = when {
        config.desiredAccuracy <= -1 -> Priority.PRIORITY_HIGH_ACCURACY
        config.desiredAccuracy <= 10 -> Priority.PRIORITY_HIGH_ACCURACY
        config.desiredAccuracy <= 100 -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        config.desiredAccuracy <= 1000 -> Priority.PRIORITY_LOW_POWER
        else -> Priority.PRIORITY_PASSIVE
    }

    private fun heartbeatMillis(): Long {
        val seconds = config.heartbeatIntervalSeconds
        return if (seconds >= 60) TimeUnit.SECONDS.toMillis(seconds.toLong()) else 0L
    }


    /**
     * Battery state, cached briefly. Reading it means a synchronous binder
     * call into the system, and it was previously done for *every* recorded
     * location — once per second in realtime mode. Battery level does not
     * change meaningfully within the cache window.
     */
    private fun batterySnapshot(): JSONObject {
        val now = System.currentTimeMillis()
        cachedBattery?.let { cached ->
            if (now - cachedBatteryAtMillis < BATTERY_CACHE_MILLIS) return cached
        }
        val snapshot = readBatterySnapshot()
        cachedBattery = snapshot
        cachedBatteryAtMillis = now
        return snapshot
    }

    private fun readBatterySnapshot(): JSONObject {
        val batteryIntent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val fraction = if (level >= 0 && scale > 0) level.toDouble() / scale else -1.0
        return JSONObject().put("is_charging", isCharging).put("level", fraction)
    }

    fun debugBeep(tone: Int) {
        if (!config.debug) return
        runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60).apply {
                startTone(tone, 200)
                mainHandler.postDelayed({ release() }, 400)
            }
        }
    }

    private fun isoTimestamp(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMillis))

    companion object {
        const val TRACKING_MODE_GEOFENCE = 0
        const val TRACKING_MODE_LOCATION = 1

        const val AUTHORIZATION_NOT_DETERMINED = 0
        const val AUTHORIZATION_RESTRICTED = 1
        const val AUTHORIZATION_DENIED = 2
        const val AUTHORIZATION_ALWAYS = 3
        const val AUTHORIZATION_WHEN_IN_USE = 4
        const val ACCURACY_AUTHORIZATION_FULL = 0

        private const val BATTERY_CACHE_MILLIS = 30_000L
        private const val PRUNE_INTERVAL_MILLIS = 5 * 60_000L

        const val ERROR_PERMISSION_DENIED = 1
        const val ERROR_POSITION_UNAVAILABLE = 2
        const val ERROR_TIMEOUT = 3

        // Holds the *application* context only (see get() below), so this is a
        // process-lifetime singleton rather than a leak — the engine must
        // outlive any Activity to keep tracking while the UI is gone.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: TrackingEngine? = null

        fun get(context: Context): TrackingEngine =
            instance ?: synchronized(this) {
                instance ?: TrackingEngine(context.applicationContext).also { instance = it }
            }
    }
}
