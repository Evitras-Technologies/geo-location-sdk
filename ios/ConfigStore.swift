import Foundation
import UIKit

/// Persisted SDK configuration + runtime state flags, backed by UserDefaults.
/// The merged config survives app restarts so tracking can resume after the
/// OS relaunches the app (significant-location-change wake, reboot, …).
final class ConfigStore {

    static let shared = ConfigStore()

    private let defaults = UserDefaults.standard
    private let configKey = "open_bg_geo.config"
    private let queue = DispatchQueue(label: "open-bg-geo.config")

    private var configDictionary: [String: Any]

    private init() {
        configDictionary = (defaults.dictionary(forKey: configKey)) ?? [:]
    }

    // MARK: - Persistence

    func merge(_ incoming: [String: Any]) {
        queue.sync {
            incoming.forEach { configDictionary[$0.key] = $0.value }
            defaults.set(configDictionary, forKey: configKey)
        }
    }

    /// Discard all persisted values, then apply `incoming` over factory defaults.
    func reset(_ incoming: [String: Any]) {
        queue.sync { configDictionary = [:] }
        merge(incoming)
    }

    func asDictionary() -> [String: Any] {
        queue.sync { configDictionary }
    }

    // MARK: - Runtime state flags

    var enabled: Bool {
        get { defaults.bool(forKey: "open_bg_geo.enabled") }
        set { defaults.set(newValue, forKey: "open_bg_geo.enabled") }
    }

    /// 1 = location + geofences, 0 = geofences-only.
    var trackingMode: Int {
        get { defaults.object(forKey: "open_bg_geo.trackingMode") as? Int ?? 1 }
        set { defaults.set(newValue, forKey: "open_bg_geo.trackingMode") }
    }

    var isMoving: Bool {
        get { defaults.bool(forKey: "open_bg_geo.isMoving") }
        set { defaults.set(newValue, forKey: "open_bg_geo.isMoving") }
    }

    var odometer: Double {
        get { defaults.double(forKey: "open_bg_geo.odometer") }
        set { defaults.set(newValue, forKey: "open_bg_geo.odometer") }
    }

    /// Stable per-installation identifier, generated once and persisted. Every
    /// HTTP upload carries it so a shared server can attribute records to the
    /// device that produced them without the host app implementing anything.
    /// Identifies an installation, not a person.
    var deviceId: String {
        if let existing = defaults.string(forKey: "open_bg_geo.deviceId") {
            return existing
        }
        let generated = UUID().uuidString
        defaults.set(generated, forKey: "open_bg_geo.deviceId")
        return generated
    }

    var deviceName: String { UIDevice.current.model }

    /// Device descriptor merged into every HTTP upload body.
    func deviceDictionary() -> [String: Any] {
        [
            "id": deviceId,
            "name": deviceName,
            "model": UIDevice.current.model,
            "manufacturer": "Apple",
            "platform": "ios",
            "os_version": UIDevice.current.systemVersion,
        ]
    }

    // MARK: - Typed getters with factory defaults

    private func value<T>(_ key: String, _ fallback: T) -> T {
        queue.sync { configDictionary[key] as? T ?? fallback }
    }

    private func number(_ key: String, _ fallback: Double) -> Double {
        queue.sync { (configDictionary[key] as? NSNumber)?.doubleValue ?? fallback }
    }

    var desiredAccuracy: Double { number("desiredAccuracy", -1) }
    var distanceFilter: Double { number("distanceFilter", 10) }
    var stationaryRadius: Double { max(number("stationaryRadius", 25), 25) }
    var disableElasticity: Bool { value("disableElasticity", false) }
    var elasticityMultiplier: Double { number("elasticityMultiplier", 1) }
    var stopAfterElapsedMinutes: Double { number("stopAfterElapsedMinutes", 0) }
    var desiredOdometerAccuracy: Double { number("desiredOdometerAccuracy", 100) }
    var useSignificantChangesOnly: Bool { value("useSignificantChangesOnly", false) }
    var showsBackgroundLocationIndicator: Bool { value("showsBackgroundLocationIndicator", false) }
    var pausesLocationUpdatesAutomatically: Bool { value("pausesLocationUpdatesAutomatically", false) }
    var activityType: Int { Int(number("activityType", 1)) }
    var locationAuthorizationRequest: String { value("locationAuthorizationRequest", "Always") }

    var isMovingOnStart: Bool { value("isMoving", false) }
    var stopTimeoutMinutes: Double { number("stopTimeout", 5) }
    var minimumActivityRecognitionConfidence: Int {
        Int(number("minimumActivityRecognitionConfidence", 70))
    }
    var disableStopDetection: Bool { value("disableStopDetection", false) }
    var disableMotionActivityUpdates: Bool { value("disableMotionActivityUpdates", false) }
    var motionTriggerDelayMinutes: Double { number("motionTriggerDelay", 0) }

    var url: String { value("url", "") }
    var httpMethod: String { value("method", "POST") }
    var httpRootProperty: String { value("httpRootProperty", "location") }
    var params: [String: Any] { value("params", [:]) }
    var headers: [String: Any] { value("headers", [:]) }
    var extras: [String: Any] { value("extras", [:]) }
    var autoSync: Bool { value("autoSync", true) }
    var autoSyncThreshold: Int { Int(number("autoSyncThreshold", 0)) }
    var batchSync: Bool { value("batchSync", false) }
    var maxBatchSize: Int { Int(number("maxBatchSize", -1)) }
    var maxDaysToPersist: Int { Int(number("maxDaysToPersist", 14)) }
    var maxRecordsToPersist: Int { Int(number("maxRecordsToPersist", -1)) }
    var persistMode: Int { Int(number("persistMode", 2)) }
    var httpTimeout: Double { number("httpTimeout", 60000) }
    var locationsOrderDirection: String { value("locationsOrderDirection", "ASC") }

    var stopOnTerminate: Bool { value("stopOnTerminate", true) }
    var startOnBoot: Bool { value("startOnBoot", false) }
    var heartbeatIntervalSeconds: Double { number("heartbeatInterval", 0) }
    /// Seconds between fresh fixes recorded while stationary; 0 disables.
    var stationaryUpdateIntervalSeconds: Double {
        let value = number("stationaryUpdateInterval", 0)
        return value > 0 ? min(max(value, 5), 3600) : 0
    }
    var preventSuspend: Bool { value("preventSuspend", false) }

    var geofenceProximityRadius: Double { number("geofenceProximityRadius", 1000) }
    var geofenceInitialTriggerEntry: Bool { value("geofenceInitialTriggerEntry", true) }

    var debug: Bool { value("debug", false) }
    var logMaxDays: Int { Int(number("logMaxDays", 3)) }

    /// The full state document returned to JS: config + live flags.
    func asStateDictionary() -> [String: Any] {
        var state = asDictionary()
        state["enabled"] = enabled
        state["trackingMode"] = trackingMode
        state["isMoving"] = isMoving
        state["odometer"] = odometer
        state["schedulerEnabled"] = false
        state["didDeviceReboot"] = false
        return state
    }
}
