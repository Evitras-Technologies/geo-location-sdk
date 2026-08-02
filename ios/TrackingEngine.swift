import CoreLocation
import CoreMotion
import Foundation
import Network
import UIKit
import AudioToolbox

/// Receives SDK events for forwarding to JavaScript. When no sink is attached
/// (JS not running) events are dropped — persistence and HTTP upload still
/// happen natively.
protocol TrackingEngineEventSink: AnyObject {
    func dispatch(event: String, payload: Any)
}

/// The core tracking state machine for iOS.
///
/// Motion model mirrors Android:
///  - *stationary*: continuous GPS off; a stationary region + significant
///    location changes + CMMotionActivityManager watch for movement.
///  - *moving*: continuous updates filtered by `distanceFilter`; after
///    `stopTimeout` minutes of detected stillness, return to *stationary*.
final class TrackingEngine: NSObject, CLLocationManagerDelegate {

    static let shared = TrackingEngine()

    weak var eventSink: TrackingEngineEventSink?

    static let trackingModeGeofence = 0
    static let trackingModeLocation = 1

    static let errorPermissionDenied = 1
    static let errorPositionUnavailable = 2
    static let errorTimeout = 3

    static let stationaryRegionIdentifier = "__open_bg_geo_stationary__"
    /// iOS caps monitored regions at 20 per app; one slot is reserved for the
    /// stationary region.
    static let maxActiveGeofences = 19

    private let locationManager = CLLocationManager()
    private let motionActivityManager = CMMotionActivityManager()
    private let pathMonitor = NWPathMonitor()

    private var config: ConfigStore { ConfigStore.shared }
    private var storage: LocationStorage { LocationStorage.shared }
    private var uploader: HttpUploader { HttpUploader.shared }

    private var continuousUpdatesActive = false
    private var lastRecordedLocation: CLLocation?
    private var lastRecordedJson: [String: Any]?
    // "unknown"/0 until CoreMotion actually reports an activity — seeding a
    // confident "still" would fabricate data and mask the case where motion
    // updates never arrive (permission denied, unsupported hardware).
    private var lastActivityType = "unknown"
    private var lastActivityConfidence = 0
    private var currentSpeedBand = 0
    private var lastReportedConnectivity: Bool?

    private var stopTimeoutWork: DispatchWorkItem?
    private var motionTriggerWork: DispatchWorkItem?
    private var stopAfterElapsedWork: DispatchWorkItem?
    private var heartbeatTimer: Timer?
    private var stationaryUpdateTimer: Timer?

    private var singleFixCallbacks: [(CLLocation?) -> Void] = []
    private var singleFixTimeoutWork: DispatchWorkItem?
    private var permissionCallbacks: [(CLAuthorizationStatus) -> Void] = []
    private var watchOptions: [String: Any]?
    /// Last known containment per polygon geofence, for ENTER/EXIT edges.
    private var polygonInsideState: [String: Bool] = [:]

    private override init() {
        super.init()
        locationManager.delegate = self
        locationManager.allowsBackgroundLocationUpdates = hasBackgroundLocationCapability()
        UIDevice.current.isBatteryMonitoringEnabled = true
        startSystemMonitors()
    }

    // MARK: - Lifecycle

    func start(trackingMode: Int) {
        config.trackingMode = trackingMode
        config.enabled = true
        applyLocationManagerSettings()
        requestAuthorizationIfNeeded()

        if trackingMode == TrackingEngine.trackingModeLocation {
            if !config.stopOnTerminate {
                // Relaunches the app after termination when the device moves ~500m.
                locationManager.startMonitoringSignificantLocationChanges()
            }
            if !config.disableMotionActivityUpdates {
                startMotionActivityUpdates()
            }
            scheduleStopAfterElapsed()
            setMoving(config.isMovingOnStart || config.isMoving, isInitial: true)
        }
        refreshGeofenceRegistrations()
        dispatch(event: "enabledchange", payload: true)
        debugBeep(1057)
    }

    func stop() {
        config.enabled = false
        config.isMoving = false
        stopTimeoutWork?.cancel()
        motionTriggerWork?.cancel()
        stopAfterElapsedWork?.cancel()
        stopHeartbeat()
        stopStationaryUpdates()
        stopContinuousUpdates()
        locationManager.stopMonitoringSignificantLocationChanges()
        motionActivityManager.stopActivityUpdates()
        stopMonitoringAllRegions()
        dispatch(event: "enabledchange", payload: false)
        debugBeep(1058)
    }

    func changePace(_ isMoving: Bool) {
        guard config.enabled else { return }
        setMoving(isMoving)
    }

    /// Re-apply live-tunable settings (distanceFilter, accuracy, …) after `setConfig`.
    func onConfigChanged() {
        guard config.enabled, config.trackingMode == TrackingEngine.trackingModeLocation else {
            return
        }
        applyLocationManagerSettings()
        if config.isMoving {
            startContinuousUpdates()
            return
        }
        // Stationary: pick up a newly configured interval right away, and
        // engage continuous tracking if stop-detection was just disabled.
        startStationaryUpdatesIfConfigured()
        if config.disableStopDetection { setMoving(true) }
    }

    // MARK: - Motion state machine

    private func setMoving(_ moving: Bool, isInitial: Bool = false) {
        guard config.enabled, config.trackingMode == TrackingEngine.trackingModeLocation else {
            return
        }
        if !isInitial && config.isMoving == moving { return }
        config.isMoving = moving
        stopTimeoutWork?.cancel()
        motionTriggerWork?.cancel()

        if moving {
            stopHeartbeat()
            locationManager.stopMonitoring(
                for: CLCircularRegion(
                    center: lastRecordedLocation?.coordinate ?? CLLocationCoordinate2D(),
                    radius: config.stationaryRadius,
                    identifier: TrackingEngine.stationaryRegionIdentifier
                )
            )
            startContinuousUpdates()
        } else {
            stopContinuousUpdates()
            startHeartbeatIfConfigured()
            startStationaryUpdatesIfConfigured()
        }

        // Record the transition with a fresh one-shot fix.
        fetchSingleFix(timeoutMillis: 30_000) { [weak self] location in
            guard let self, let location else { return }
            if !moving {
                self.registerStationaryRegion(around: location.coordinate)
            }
            let record = self.buildLocationRecord(location: location, event: "motionchange")
            self.persistRecord(record, isGeofenceEvent: false)
            self.dispatch(
                event: "motionchange",
                payload: ["isMoving": moving, "location": record]
            )
            self.dispatch(event: "location", payload: record)
            self.autoSyncIfDue()
        }
        debugBeep(moving ? 1113 : 1114)
    }

    private func onActivitySample(type: String, confidence: Int) {
        let changed = type != lastActivityType
        lastActivityType = type
        lastActivityConfidence = confidence
        if changed {
            dispatch(event: "activitychange", payload: ["activity": type, "confidence": confidence])
        }
        guard config.enabled,
              config.trackingMode == TrackingEngine.trackingModeLocation,
              !config.disableStopDetection,
              confidence >= config.minimumActivityRecognitionConfidence
        else { return }

        let indicatesMovement = type != "still" && type != "unknown"
        if indicatesMovement && !config.isMoving {
            let delaySeconds = config.motionTriggerDelayMinutes * 60
            motionTriggerWork?.cancel()
            if delaySeconds > 0 {
                let work = DispatchWorkItem { [weak self] in self?.setMoving(true) }
                motionTriggerWork = work
                DispatchQueue.main.asyncAfter(deadline: .now() + delaySeconds, execute: work)
            } else {
                setMoving(true)
            }
        } else if type == "still" && config.isMoving {
            stopTimeoutWork?.cancel()
            let work = DispatchWorkItem { [weak self] in self?.setMoving(false) }
            stopTimeoutWork = work
            DispatchQueue.main.asyncAfter(
                deadline: .now() + config.stopTimeoutMinutes * 60,
                execute: work
            )
        } else if indicatesMovement && config.isMoving {
            stopTimeoutWork?.cancel()
        }
    }

    private func startMotionActivityUpdates() {
        guard CMMotionActivityManager.isActivityAvailable() else { return }
        motionActivityManager.startActivityUpdates(to: .main) { [weak self] activity in
            guard let self, let activity else { return }
            let type: String
            if activity.automotive { type = "in_vehicle" }
            else if activity.cycling { type = "on_bicycle" }
            else if activity.running { type = "running" }
            else if activity.walking { type = "walking" }
            else if activity.stationary { type = "still" }
            else { type = "unknown" }

            let confidence: Int
            switch activity.confidence {
            case .high: confidence = 100
            case .medium: confidence = 66
            default: confidence = 33
            }
            self.onActivitySample(type: type, confidence: confidence)
        }
    }

    // MARK: - Continuous updates

    private func applyLocationManagerSettings() {
        locationManager.desiredAccuracy = mapDesiredAccuracy()
        locationManager.distanceFilter = effectiveDistanceFilter()
        locationManager.pausesLocationUpdatesAutomatically =
            config.pausesLocationUpdatesAutomatically
        locationManager.activityType =
            CLActivityType(rawValue: config.activityType) ?? .other
        if #available(iOS 11.0, *) {
            locationManager.showsBackgroundLocationIndicator =
                config.showsBackgroundLocationIndicator
        }
    }

    private func startContinuousUpdates() {
        applyLocationManagerSettings()
        if config.useSignificantChangesOnly {
            locationManager.startMonitoringSignificantLocationChanges()
        } else {
            locationManager.startUpdatingLocation()
        }
        continuousUpdatesActive = true
    }

    private func stopContinuousUpdates() {
        continuousUpdatesActive = false
        locationManager.stopUpdatingLocation()
        // `preventSuspend` keeps a low-power location stream running while
        // stationary so heartbeat timers continue to fire in the background.
        if config.enabled && config.preventSuspend {
            locationManager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
            locationManager.distanceFilter = CLLocationDistanceMax
            locationManager.startUpdatingLocation()
        }
    }

    // MARK: - CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }

        if !singleFixCallbacks.isEmpty {
            let callbacks = singleFixCallbacks
            singleFixCallbacks = []
            singleFixTimeoutWork?.cancel()
            callbacks.forEach { $0(location) }
            if !continuousUpdatesActive && watchOptions == nil {
                locationManager.stopUpdatingLocation()
            }
        }

        if let options = watchOptions {
            let record = buildLocationRecord(location: location, event: nil)
            if options["persist"] as? Bool == true {
                persistRecord(record, isGeofenceEvent: false)
            }
            dispatch(event: "watchposition", payload: record)
        }

        guard continuousUpdatesActive, config.enabled else { return }
        guard passesFilters(location) else { return }
        accumulateOdometer(location)
        maybeAdjustElasticity(location)

        lastRecordedLocation = location
        let record = buildLocationRecord(location: location, event: nil)
        lastRecordedJson = record
        persistRecord(record, isGeofenceEvent: false)
        dispatch(event: "location", payload: record)
        evaluateGeofenceProximity(around: location)
        evaluatePolygonGeofences(location)
        autoSyncIfDue()
        // No beep per location — see the Android engine: at realtime rates it
        // is one tone per second. Debug tones mark life-cycle events only.
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if !singleFixCallbacks.isEmpty {
            let callbacks = singleFixCallbacks
            singleFixCallbacks = []
            callbacks.forEach { $0(nil) }
        }
    }

    func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        handleRegionTransition(region: region, action: "ENTER")
    }

    func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        if region.identifier == TrackingEngine.stationaryRegionIdentifier {
            if config.enabled && !config.isMoving { setMoving(true) }
            return
        }
        handleRegionTransition(region: region, action: "EXIT")
    }

    func locationManager(
        _ manager: CLLocationManager,
        didChangeAuthorization status: CLAuthorizationStatus
    ) {
        let callbacks = permissionCallbacks
        permissionCallbacks = []
        callbacks.forEach { $0(status) }
        dispatch(event: "providerchange", payload: buildProviderState())
    }

    // MARK: - Geofences

    func addGeofence(_ json: [String: Any]) {
        guard let identifier = json["identifier"] as? String else { return }
        storage.upsertGeofence(identifier: identifier, json: normalizePolygon(json))
        refreshGeofenceRegistrations()
    }

    /// Polygon geofences carry `vertices` instead of a centre and radius.
    /// CoreLocation only monitors circles, so derive the bounding circle here;
    /// exact containment is decided by `PolygonGeometry.contains`.
    private func normalizePolygon(_ json: [String: Any]) -> [String: Any] {
        guard let vertices = PolygonGeometry.parseVertices(json) else { return json }
        let centre = PolygonGeometry.centroid(vertices)
        var normalized = json
        normalized["latitude"] = centre.latitude
        normalized["longitude"] = centre.longitude
        normalized["radius"] = PolygonGeometry.boundingRadius(vertices, centre: centre)
        normalized["isPolygon"] = true
        return normalized
    }

    /// Exact polygon transitions, evaluated on each recorded location — the OS
    /// only wakes us for the bounding circle.
    private func evaluatePolygonGeofences(_ location: CLLocation) {
        for json in storage.allGeofences() {
            guard json["isPolygon"] as? Bool == true,
                  let identifier = json["identifier"] as? String,
                  let vertices = PolygonGeometry.parseVertices(json)
            else { continue }

            let isInside = PolygonGeometry.contains(
                vertices,
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude
            )
            let wasInside = polygonInsideState[identifier]
            guard wasInside != isInside else { continue }
            polygonInsideState[identifier] = isInside
            if wasInside != nil || isInside {
                handlePolygonTransition(identifier: identifier, isInside: isInside, location: location)
            }
        }
    }

    private func handlePolygonTransition(identifier: String, isInside: Bool, location: CLLocation) {
        let geofenceJson = storage.geofence(identifier: identifier)
        var record = buildLocationRecord(location: location, event: "geofence")
        var detail: [String: Any] = [
            "identifier": identifier,
            "action": isInside ? "ENTER" : "EXIT",
        ]
        if let extras = geofenceJson?["extras"] { detail["extras"] = extras }
        record["geofence"] = detail
        persistRecord(record, isGeofenceEvent: true)

        var payload: [String: Any] = [
            "identifier": identifier,
            "action": isInside ? "ENTER" : "EXIT",
            "location": record,
        ]
        if let extras = geofenceJson?["extras"] { payload["extras"] = extras }
        dispatch(event: "geofence", payload: payload)
        autoSyncIfDue()
    }

    func removeGeofence(identifier: String) -> Bool {
        let removed = storage.deleteGeofence(identifier: identifier)
        for region in locationManager.monitoredRegions where region.identifier == identifier {
            locationManager.stopMonitoring(for: region)
        }
        return removed
    }

    func removeAllGeofences() {
        storage.deleteAllGeofences()
        stopMonitoringAllRegions(keepStationary: true)
    }

    private func refreshGeofenceRegistrations() {
        guard config.enabled else { return }
        let geofences = storage.allGeofences()
        let toActivate = Array(geofences.prefix(TrackingEngine.maxActiveGeofences))
        registerRegions(for: toActivate)
    }

    private func evaluateGeofenceProximity(around location: CLLocation) {
        let geofences = storage.allGeofences()
        guard geofences.count > TrackingEngine.maxActiveGeofences else { return }

        let nearest = geofences
            .compactMap { json -> ([String: Any], CLLocationDistance)? in
                guard let latitude = json["latitude"] as? Double,
                      let longitude = json["longitude"] as? Double else { return nil }
                let distance = location.distance(
                    from: CLLocation(latitude: latitude, longitude: longitude)
                )
                return (json, distance)
            }
            .filter { $0.1 <= config.geofenceProximityRadius }
            .sorted { $0.1 < $1.1 }
            .prefix(TrackingEngine.maxActiveGeofences)
            .map { $0.0 }

        let nearestIdentifiers = Set(nearest.compactMap { $0["identifier"] as? String })
        let currentIdentifiers = Set(
            locationManager.monitoredRegions
                .map(\.identifier)
                .filter { $0 != TrackingEngine.stationaryRegionIdentifier }
        )
        guard nearestIdentifiers != currentIdentifiers else { return }

        let turnedOff = currentIdentifiers.subtracting(nearestIdentifiers)
        let turnedOn = nearest.filter {
            guard let id = $0["identifier"] as? String else { return false }
            return !currentIdentifiers.contains(id)
        }
        for region in locationManager.monitoredRegions
        where turnedOff.contains(region.identifier) {
            locationManager.stopMonitoring(for: region)
        }
        registerRegions(for: turnedOn)
        dispatch(event: "geofenceschange", payload: ["on": turnedOn, "off": Array(turnedOff)])
    }

    private func registerRegions(for geofences: [[String: Any]]) {
        for json in geofences {
            guard let identifier = json["identifier"] as? String,
                  let latitude = json["latitude"] as? Double,
                  let longitude = json["longitude"] as? Double,
                  let radius = (json["radius"] as? NSNumber)?.doubleValue
            else { continue }
            let region = CLCircularRegion(
                center: CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
                radius: max(radius, 100),
                identifier: identifier
            )
            region.notifyOnEntry = json["notifyOnEntry"] as? Bool ?? true
            region.notifyOnExit = json["notifyOnExit"] as? Bool ?? false
            locationManager.startMonitoring(for: region)
            if config.geofenceInitialTriggerEntry {
                locationManager.requestState(for: region)
            }
        }
    }

    func locationManager(
        _ manager: CLLocationManager,
        didDetermineState state: CLRegionState,
        for region: CLRegion
    ) {
        guard config.geofenceInitialTriggerEntry,
              state == .inside,
              region.identifier != TrackingEngine.stationaryRegionIdentifier,
              (region as? CLCircularRegion)?.notifyOnEntry == true
        else { return }
        handleRegionTransition(region: region, action: "ENTER")
    }

    private func handleRegionTransition(region: CLRegion, action: String) {
        guard region.identifier != TrackingEngine.stationaryRegionIdentifier else { return }
        let geofenceJson = storage.geofence(identifier: region.identifier)
        var record: [String: Any] = lastRecordedJson ?? [:]
        if let location = locationManager.location {
            record = buildLocationRecord(location: location, event: "geofence")
            var detail: [String: Any] = ["identifier": region.identifier, "action": action]
            if let extras = geofenceJson?["extras"] { detail["extras"] = extras }
            record["geofence"] = detail
        }
        persistRecord(record, isGeofenceEvent: true)

        var payload: [String: Any] = [
            "identifier": region.identifier,
            "action": action,
            "location": record,
        ]
        if let extras = geofenceJson?["extras"] { payload["extras"] = extras }
        dispatch(event: "geofence", payload: payload)
        autoSyncIfDue()
        debugBeep(1016)
    }

    private func registerStationaryRegion(around coordinate: CLLocationCoordinate2D) {
        let region = CLCircularRegion(
            center: coordinate,
            radius: config.stationaryRadius,
            identifier: TrackingEngine.stationaryRegionIdentifier
        )
        region.notifyOnEntry = false
        region.notifyOnExit = true
        locationManager.startMonitoring(for: region)
    }

    private func stopMonitoringAllRegions(keepStationary: Bool = false) {
        for region in locationManager.monitoredRegions {
            if keepStationary && region.identifier == TrackingEngine.stationaryRegionIdentifier {
                continue
            }
            locationManager.stopMonitoring(for: region)
        }
    }

    // MARK: - One-shot fixes

    func fetchSingleFix(timeoutMillis: Double, callback: @escaping (CLLocation?) -> Void) {
        singleFixCallbacks.append(callback)
        singleFixTimeoutWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            let callbacks = self.singleFixCallbacks
            self.singleFixCallbacks = []
            callbacks.forEach { $0(nil) }
        }
        singleFixTimeoutWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + timeoutMillis / 1000, execute: work)
        if !continuousUpdatesActive {
            locationManager.startUpdatingLocation()
        }
    }

    func getCurrentPosition(
        options: [String: Any],
        onSuccess: @escaping ([String: Any]) -> Void,
        onError: @escaping (Int) -> Void
    ) {
        let status = authorizationStatus()
        guard status != .denied && status != .restricted else {
            onError(TrackingEngine.errorPermissionDenied)
            return
        }
        let timeout = (options["timeout"] as? NSNumber)?.doubleValue ?? 30_000
        let persist = options["persist"] as? Bool ?? true
        let maximumAge = (options["maximumAge"] as? NSNumber)?.doubleValue ?? 0

        if maximumAge > 0,
           let cached = locationManager.location,
           Date().timeIntervalSince(cached.timestamp) * 1000 <= maximumAge {
            deliverCurrentPosition(cached, persist: persist, options: options, onSuccess: onSuccess)
            return
        }

        requestAuthorizationIfNeeded()
        fetchSingleFix(timeoutMillis: timeout) { [weak self] location in
            guard let self else { return }
            guard let location else {
                onError(TrackingEngine.errorTimeout)
                return
            }
            self.deliverCurrentPosition(
                location, persist: persist, options: options, onSuccess: onSuccess
            )
        }
    }

    private func deliverCurrentPosition(
        _ location: CLLocation,
        persist: Bool,
        options: [String: Any],
        onSuccess: @escaping ([String: Any]) -> Void
    ) {
        accumulateOdometer(location)
        lastRecordedLocation = location
        var record = buildLocationRecord(location: location, event: nil)
        if let extras = options["extras"] as? [String: Any] {
            var merged = (record["extras"] as? [String: Any]) ?? [:]
            extras.forEach { merged[$0.key] = $0.value }
            record["extras"] = merged
        }
        lastRecordedJson = record
        if persist {
            persistRecord(record, isGeofenceEvent: false)
            dispatch(event: "location", payload: record)
            autoSyncIfDue()
        }
        onSuccess(record)
    }

    // MARK: - watchPosition

    func watchPosition(options: [String: Any]) {
        watchOptions = options
        locationManager.startUpdatingLocation()
    }

    func stopWatchPosition() {
        watchOptions = nil
        if !continuousUpdatesActive {
            locationManager.stopUpdatingLocation()
        }
    }

    // MARK: - Permissions & provider state

    func requestPermission(completion: @escaping (CLAuthorizationStatus) -> Void) {
        let status = authorizationStatus()
        if status != .notDetermined {
            completion(status)
            return
        }
        permissionCallbacks.append(completion)
        requestAuthorizationIfNeeded(force: true)
    }

    private func requestAuthorizationIfNeeded(force: Bool = false) {
        let status = authorizationStatus()
        guard force || status == .notDetermined else { return }
        if config.locationAuthorizationRequest == "WhenInUse" {
            locationManager.requestWhenInUseAuthorization()
        } else {
            locationManager.requestAlwaysAuthorization()
        }
    }

    func authorizationStatus() -> CLAuthorizationStatus {
        if #available(iOS 14.0, *) {
            return locationManager.authorizationStatus
        }
        return CLLocationManager.authorizationStatus()
    }

    func buildProviderState() -> [String: Any] {
        let status = authorizationStatus()
        let mappedStatus: Int
        switch status {
        case .notDetermined: mappedStatus = 0
        case .restricted: mappedStatus = 1
        case .denied: mappedStatus = 2
        case .authorizedAlways: mappedStatus = 3
        case .authorizedWhenInUse: mappedStatus = 4
        @unknown default: mappedStatus = 0
        }
        var accuracyAuthorization = 0
        if #available(iOS 14.0, *) {
            accuracyAuthorization = locationManager.accuracyAuthorization == .reducedAccuracy ? 1 : 0
        }
        let servicesEnabled = CLLocationManager.locationServicesEnabled()
        let authorized = status == .authorizedAlways || status == .authorizedWhenInUse
        return [
            "enabled": servicesEnabled && authorized,
            "status": mappedStatus,
            "network": servicesEnabled,
            "gps": servicesEnabled,
            "accuracyAuthorization": accuracyAuthorization,
        ]
    }

    // MARK: - Heartbeat

    private func startHeartbeatIfConfigured() {
        let interval = config.heartbeatIntervalSeconds
        guard interval >= 60 else { return }
        heartbeatTimer = Timer.scheduledTimer(
            withTimeInterval: interval, repeats: true
        ) { [weak self] _ in
            guard let self, let location = self.lastRecordedJson else { return }
            self.dispatch(event: "heartbeat", payload: ["location": location])
        }
    }

    private func stopHeartbeat() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
    }

    /// While stationary, record a fresh fix periodically so the server keeps
    /// receiving "still here" positions instead of silence. Unlike the
    /// heartbeat event these are real fixes: persisted, uploaded and emitted
    /// as `location`.
    private func startStationaryUpdatesIfConfigured() {
        stopStationaryUpdates()
        let interval = config.stationaryUpdateIntervalSeconds
        guard interval > 0 else { return }
        stationaryUpdateTimer = Timer.scheduledTimer(
            withTimeInterval: interval, repeats: true
        ) { [weak self] _ in
            guard let self, self.config.enabled, !self.config.isMoving else { return }
            self.fetchSingleFix(timeoutMillis: 20_000) { location in
                guard let location, self.config.enabled, !self.config.isMoving else { return }
                self.accumulateOdometer(location)
                self.lastRecordedLocation = location
                let record = self.buildLocationRecord(location: location, event: "heartbeat")
                self.lastRecordedJson = record
                self.persistRecord(record, isGeofenceEvent: false)
                self.dispatch(event: "location", payload: record)
                self.autoSyncIfDue()
            }
        }
    }

    private func stopStationaryUpdates() {
        stationaryUpdateTimer?.invalidate()
        stationaryUpdateTimer = nil
    }

    // MARK: - Record building & persistence

    func buildLocationRecord(location: CLLocation, event: String?) -> [String: Any] {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]

        var coords: [String: Any] = [
            "latitude": location.coordinate.latitude,
            "longitude": location.coordinate.longitude,
            "accuracy": location.horizontalAccuracy,
            "speed": location.speed >= 0 ? location.speed : -1,
            "heading": location.course >= 0 ? location.course : -1,
            "altitude": location.verticalAccuracy > 0 ? location.altitude : -1,
            "altitude_accuracy": location.verticalAccuracy > 0 ? location.verticalAccuracy : -1,
            "ellipsoidal_altitude": location.verticalAccuracy > 0 ? location.altitude : -1,
        ]
        if #available(iOS 10.0, *) {
            coords["speed_accuracy"] = location.speedAccuracy >= 0 ? location.speedAccuracy : -1
        }
        if #available(iOS 13.4, *) {
            coords["heading_accuracy"] = location.courseAccuracy >= 0 ? location.courseAccuracy : -1
        }

        let batteryLevel = UIDevice.current.batteryLevel
        let batteryState = UIDevice.current.batteryState
        let battery: [String: Any] = [
            "is_charging": batteryState == .charging || batteryState == .full,
            "level": batteryLevel >= 0 ? Double(batteryLevel) : -1,
        ]

        var record: [String: Any] = [
            "timestamp": formatter.string(from: location.timestamp),
            "age": Int(Date().timeIntervalSince(location.timestamp) * 1000),
            "is_moving": config.isMoving,
            "uuid": UUID().uuidString,
            "coords": coords,
            "activity": ["type": lastActivityType, "confidence": lastActivityConfidence],
            "battery": battery,
            "odometer": config.odometer,
            "mock": false,
        ]
        if let event { record["event"] = event }
        if !config.extras.isEmpty { record["extras"] = config.extras }
        return record
    }

    func persistRecord(_ record: [String: Any], isGeofenceEvent: Bool) {
        let mode = config.persistMode
        let shouldPersist: Bool
        switch mode {
        case 0: shouldPersist = false
        case 1: shouldPersist = !isGeofenceEvent
        case -1: shouldPersist = isGeofenceEvent
        default: shouldPersist = true
        }
        guard shouldPersist else { return }
        let uuid = record["uuid"] as? String ?? UUID().uuidString
        storage.insertLocation(uuid: uuid, json: record)
        storage.pruneLocations(maxDays: config.maxDaysToPersist, maxRecords: config.maxRecordsToPersist)
    }

    private func autoSyncIfDue() {
        if uploader.shouldAutoSync() { uploader.flush() }
    }

    // MARK: - Filters, odometer, elasticity

    private func passesFilters(_ location: CLLocation) -> Bool {
        guard let previous = lastRecordedLocation else { return true }
        if previous.coordinate.latitude == location.coordinate.latitude &&
            previous.coordinate.longitude == location.coordinate.longitude {
            return false
        }
        let elapsed = location.timestamp.timeIntervalSince(previous.timestamp)
        if elapsed > 0 {
            let impliedSpeed = location.distance(from: previous) / elapsed
            if impliedSpeed > 300 { return false } // teleport filter
        }
        return true
    }

    private func accumulateOdometer(_ location: CLLocation) {
        guard let previous = lastRecordedLocation else { return }
        if location.horizontalAccuracy <= config.desiredOdometerAccuracy {
            config.odometer += location.distance(from: previous)
        }
    }

    private func maybeAdjustElasticity(_ location: CLLocation) {
        guard !config.disableElasticity, location.speed >= 0 else { return }
        let band = Int(location.speed / 5)
        if band != currentSpeedBand {
            currentSpeedBand = band
            locationManager.distanceFilter = effectiveDistanceFilter()
        }
    }

    private func effectiveDistanceFilter() -> Double {
        if config.disableElasticity { return config.distanceFilter }
        let scale = 1 + Double(currentSpeedBand) * config.elasticityMultiplier
        return config.distanceFilter * scale
    }

    private func mapDesiredAccuracy() -> CLLocationAccuracy {
        switch config.desiredAccuracy {
        case ..<(-1): return kCLLocationAccuracyBestForNavigation
        case -1: return kCLLocationAccuracyBest
        case ...10: return kCLLocationAccuracyNearestTenMeters
        case ...100: return kCLLocationAccuracyHundredMeters
        case ...1000: return kCLLocationAccuracyKilometer
        default: return kCLLocationAccuracyThreeKilometers
        }
    }

    // MARK: - System monitors

    private func startSystemMonitors() {
        NotificationCenter.default.addObserver(
            forName: Notification.Name.NSProcessInfoPowerStateDidChange,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.dispatch(
                event: "powersavechange",
                payload: ProcessInfo.processInfo.isLowPowerModeEnabled
            )
        }
        pathMonitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let connected = path.status == .satisfied
            guard connected != self.lastReportedConnectivity else { return }
            self.lastReportedConnectivity = connected
            self.dispatch(event: "connectivitychange", payload: ["connected": connected])
            if connected { self.uploader.flush() }
        }
        pathMonitor.start(queue: DispatchQueue(label: "open-bg-geo.network"))
    }

    // MARK: - Misc helpers

    private func scheduleStopAfterElapsed() {
        stopAfterElapsedWork?.cancel()
        let minutes = config.stopAfterElapsedMinutes
        guard minutes > 0 else { return }
        let work = DispatchWorkItem { [weak self] in self?.stop() }
        stopAfterElapsedWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + minutes * 60, execute: work)
    }

    private func hasBackgroundLocationCapability() -> Bool {
        let modes = Bundle.main.object(
            forInfoDictionaryKey: "UIBackgroundModes"
        ) as? [String] ?? []
        return modes.contains("location")
    }

    func debugBeep(_ soundId: UInt32) {
        guard config.debug else { return }
        AudioServicesPlaySystemSound(soundId)
    }

    func dispatch(event: String, payload: Any) {
        eventSink?.dispatch(event: event, payload: payload)
    }
}
