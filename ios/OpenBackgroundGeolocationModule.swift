import CoreLocation
import CoreMotion
import Foundation
import MessageUI
import React
import UIKit

/// The React Native bridge. Thin by design: every method delegates to the
/// singleton `TrackingEngine`, which owns all tracking behaviour.
@objc(OpenBackgroundGeolocation)
class OpenBackgroundGeolocationModule: RCTEventEmitter, TrackingEngineEventSink {

    private var engine: TrackingEngine { TrackingEngine.shared }
    private var config: ConfigStore { ConfigStore.shared }
    private var storage: LocationStorage { LocationStorage.shared }
    private var backgroundTaskCounter = 0
    private var backgroundTasks: [Int: UIBackgroundTaskIdentifier] = [:]
    private var mailComposeDelegate: MailComposeDelegate?

    override init() {
        super.init()
        engine.eventSink = self
        HttpUploader.shared.onHttpEvent = { [weak self] payload in
            self?.dispatch(event: "http", payload: payload)
        }
    }

    override static func requiresMainQueueSetup() -> Bool { true }

    // Run all bridge methods on the main queue: CLLocationManager needs a
    // run-loop thread and several methods present UI.
    @objc var methodQueue: DispatchQueue { DispatchQueue.main }

    override func supportedEvents() -> [String] {
        [
            "location", "motionchange", "activitychange", "providerchange",
            "geofence", "geofenceschange", "heartbeat", "http",
            "connectivitychange", "powersavechange", "enabledchange",
            "notificationaction", "watchposition",
        ]
    }

    func dispatch(event: String, payload: Any) {
        sendEvent(withName: event, body: payload)
    }

    // MARK: - Lifecycle

    @objc func ready(
        _ configuration: NSDictionary,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        config.merge(configuration as? [String: Any] ?? [:])
        storage.pruneLog(maxDays: config.logMaxDays)
        // Resume tracking if it was enabled when the app was last terminated
        // (e.g. relaunched by a significant-location-change or geofence wake).
        if config.enabled {
            engine.start(trackingMode: config.trackingMode)
        }
        resolve(config.asStateDictionary())
    }

    @objc func reset(
        _ configuration: NSDictionary,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        config.reset(configuration as? [String: Any] ?? [:])
        resolve(config.asStateDictionary())
    }

    @objc func setConfig(
        _ configuration: NSDictionary,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        config.merge(configuration as? [String: Any] ?? [:])
        engine.onConfigChanged()
        resolve(config.asStateDictionary())
    }

    @objc func getState(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        resolve(config.asStateDictionary())
    }

    @objc func start(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        engine.start(trackingMode: TrackingEngine.trackingModeLocation)
        resolve(config.asStateDictionary())
    }

    @objc func stop(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        engine.stop()
        resolve(config.asStateDictionary())
    }

    @objc func startGeofences(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        engine.start(trackingMode: TrackingEngine.trackingModeGeofence)
        resolve(config.asStateDictionary())
    }

    @objc func changePace(
        _ isMoving: Bool,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        engine.changePace(isMoving)
        resolve(nil)
    }

    // MARK: - Geolocation

    @objc func getCurrentPosition(
        _ options: NSDictionary,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        engine.getCurrentPosition(
            options: options as? [String: Any] ?? [:],
            onSuccess: { record in resolve(record) },
            onError: { code in reject(String(code), "Location error: \(code)", nil) }
        )
    }

    @objc func watchPosition(
        _ options: NSDictionary,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        engine.watchPosition(options: options as? [String: Any] ?? [:])
        resolve(nil)
    }

    @objc func stopWatchPosition(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        engine.stopWatchPosition()
        resolve(nil)
    }

    @objc func getOdometer(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        resolve(config.odometer)
    }

    @objc func setOdometer(
        _ value: Double,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        config.odometer = value
        engine.getCurrentPosition(
            options: ["persist": false],
            onSuccess: { record in resolve(record) },
            onError: { code in reject(String(code), "Location error: \(code)", nil) }
        )
    }

    // MARK: - Database & HTTP

    @objc func getLocations(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        resolve(storage.allLocations(orderDirection: config.locationsOrderDirection).map(\.json))
    }

    @objc func getCount(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        resolve(storage.locationCount())
    }

    @objc func insertLocation(
        _ location: NSDictionary,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        var json = location as? [String: Any] ?? [:]
        let uuid = (json["uuid"] as? String) ?? UUID().uuidString
        json["uuid"] = uuid
        storage.insertLocation(uuid: uuid, json: json)
        resolve(uuid)
    }

    @objc func destroyLocations(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        storage.deleteAllLocations()
        resolve(nil)
    }

    @objc func destroyLocation(
        _ uuid: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        if storage.deleteLocation(uuid: uuid) {
            resolve(nil)
        } else {
            reject("404", "Location not found: \(uuid)", nil)
        }
    }

    @objc func sync(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard !config.url.isEmpty else {
            reject("500", "Cannot sync: no Config.url is set", nil)
            return
        }
        HttpUploader.shared.flush { synced in
            DispatchQueue.main.async { resolve(synced) }
        }
    }

    // MARK: - Geofencing

    @objc func addGeofence(
        _ geofence: NSDictionary,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard let json = geofence as? [String: Any],
              json["identifier"] is String,
              json["latitude"] is NSNumber,
              json["longitude"] is NSNumber,
              json["radius"] is NSNumber
        else {
            reject("422", "Geofence requires identifier, latitude, longitude and radius", nil)
            return
        }
        engine.addGeofence(json)
        resolve(nil)
    }

    @objc func addGeofences(
        _ geofences: NSArray,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        (geofences as? [[String: Any]])?.forEach { engine.addGeofence($0) }
        resolve(nil)
    }

    @objc func removeGeofence(
        _ identifier: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        _ = engine.removeGeofence(identifier: identifier)
        resolve(nil)
    }

    @objc func removeGeofences(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        engine.removeAllGeofences()
        resolve(nil)
    }

    @objc func getGeofences(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        resolve(storage.allGeofences())
    }

    @objc func getGeofence(
        _ identifier: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        if let geofence = storage.geofence(identifier: identifier) {
            resolve(geofence)
        } else {
            reject("404", "Geofence not found: \(identifier)", nil)
        }
    }

    @objc func geofenceExists(
        _ identifier: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        resolve(storage.geofence(identifier: identifier) != nil)
    }

    // MARK: - Permissions & device state

    @objc func requestPermission(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        engine.requestPermission { status in
            let mapped: Int
            switch status {
            case .notDetermined: mapped = 0
            case .restricted: mapped = 1
            case .denied: mapped = 2
            case .authorizedAlways: mapped = 3
            case .authorizedWhenInUse: mapped = 4
            @unknown default: mapped = 0
            }
            if status == .denied || status == .restricted {
                reject(String(mapped), "Location permission denied", nil)
            } else {
                resolve(mapped)
            }
        }
    }

    @objc func requestTemporaryFullAccuracy(
        _ purpose: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        if #available(iOS 14.0, *) {
            let manager = CLLocationManager()
            manager.requestTemporaryFullAccuracyAuthorization(withPurposeKey: purpose) { _ in
                DispatchQueue.main.async {
                    resolve(manager.accuracyAuthorization == .reducedAccuracy ? 1 : 0)
                }
            }
        } else {
            resolve(0)
        }
    }

    @objc func getProviderState(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        resolve(engine.buildProviderState())
    }

    /// iOS offers no in-app prompt to enable Location Services — the user must
    /// use Settings. Report the current state so callers can guide them.
    @objc func requestLocationServices(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        resolve(CLLocationManager.locationServicesEnabled())
    }

    @objc func isPowerSaveMode(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        resolve(ProcessInfo.processInfo.isLowPowerModeEnabled)
    }

    @objc func getSensors(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        let motion = CMMotionManager()
        resolve([
            "platform": "ios",
            "accelerometer": motion.isAccelerometerAvailable,
            "gyroscope": motion.isGyroAvailable,
            "magnetometer": motion.isMagnetometerAvailable,
            "motion_hardware": CMMotionActivityManager.isActivityAvailable(),
        ])
    }

    @objc func getDeviceInfo(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        resolve([
            "model": UIDevice.current.model,
            "manufacturer": "Apple",
            "version": UIDevice.current.systemVersion,
            "platform": "ios",
            "framework": "react-native",
            "deviceId": config.deviceId,
        ])
    }

    @objc func getDeviceId(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        resolve(config.deviceId)
    }

    // MARK: - Background tasks

    @objc func startBackgroundTask(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        backgroundTaskCounter += 1
        let taskId = backgroundTaskCounter
        let osTask = UIApplication.shared.beginBackgroundTask(
            withName: "OpenBackgroundGeolocation-\(taskId)"
        ) { [weak self] in
            self?.endBackgroundTask(taskId)
        }
        backgroundTasks[taskId] = osTask
        resolve(taskId)
    }

    @objc func stopBackgroundTask(
        _ taskId: Double,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        endBackgroundTask(Int(taskId))
        resolve(nil)
    }

    private func endBackgroundTask(_ taskId: Int) {
        if let osTask = backgroundTasks.removeValue(forKey: taskId) {
            UIApplication.shared.endBackgroundTask(osTask)
        }
    }

    // MARK: - Debug & logging

    @objc func playSound(
        _ soundId: Double,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        AudioServicesPlaySystemSound(UInt32(soundId))
        resolve(nil)
    }

    @objc func log(
        _ level: String,
        message: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        storage.appendLog(level: level, message: message)
        resolve(nil)
    }

    @objc func getLog(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        resolve(storage.readLog())
    }

    @objc func destroyLog(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        storage.destroyLog()
        resolve(nil)
    }

    @objc func emailLog(
        _ email: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard MFMailComposeViewController.canSendMail() else {
            reject("500", "No email account configured on this device", nil)
            return
        }
        guard let presenter = RCTPresentedViewController() else {
            reject("500", "No view controller available to present mail composer", nil)
            return
        }
        let composer = MFMailComposeViewController()
        let delegate = MailComposeDelegate { [weak self] in
            self?.mailComposeDelegate = nil
            resolve(nil)
        }
        mailComposeDelegate = delegate
        composer.mailComposeDelegate = delegate
        composer.setToRecipients([email])
        composer.setSubject("BackgroundGeolocation log")
        composer.setMessageBody(storage.readLog(), isHTML: false)
        presenter.present(composer, animated: true)
    }

    @objc func showSettings(
        _ setting: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard let url = URL(string: UIApplication.openSettingsURLString) else {
            reject("500", "Unable to open settings", nil)
            return
        }
        UIApplication.shared.open(url)
        resolve(nil)
    }
}

/// Dismisses the mail composer and reports completion back to the module.
private final class MailComposeDelegate: NSObject, MFMailComposeViewControllerDelegate {
    private let onFinish: () -> Void

    init(onFinish: @escaping () -> Void) {
        self.onFinish = onFinish
    }

    func mailComposeController(
        _ controller: MFMailComposeViewController,
        didFinishWith result: MFMailComposeResult,
        error: Error?
    ) {
        controller.dismiss(animated: true)
        onFinish()
    }
}
