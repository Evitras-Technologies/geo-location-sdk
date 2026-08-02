import Foundation

/// Uploads persisted locations to the configured `Config.url`.
///
/// Records are deleted from local storage only after the server responds 2xx,
/// giving at-least-once delivery. Uploads run on a serial background queue;
/// concurrent flush requests coalesce.
final class HttpUploader {

    static let shared = HttpUploader()

    /// Set by the module to forward `http` events to JavaScript.
    var onHttpEvent: (([String: Any]) -> Void)?

    private let queue = DispatchQueue(label: "open-bg-geo.http")
    private var flushInProgress = false
    private let stateLock = NSLock()

    private var config: ConfigStore { ConfigStore.shared }
    private var storage: LocationStorage { LocationStorage.shared }

    /// Upload everything pending. Safe to call from any thread.
    func flush(completion: (([[String: Any]]) -> Void)? = nil) {
        guard !config.url.isEmpty else {
            completion?([])
            return
        }
        stateLock.lock()
        if flushInProgress {
            stateLock.unlock()
            completion?([])
            return
        }
        flushInProgress = true
        stateLock.unlock()

        queue.async { [weak self] in
            guard let self else { return }
            var synced: [[String: Any]] = []
            if self.config.batchSync {
                self.uploadBatches(&synced)
            } else {
                self.uploadOneByOne(&synced)
            }
            self.stateLock.lock()
            self.flushInProgress = false
            self.stateLock.unlock()
            completion?(synced)
        }
    }

    /// `true` when enough records have accumulated to justify an auto-sync.
    func shouldAutoSync() -> Bool {
        guard config.autoSync, !config.url.isEmpty else { return false }
        let threshold = config.autoSyncThreshold
        return threshold <= 0 || storage.locationCount() >= threshold
    }

    // MARK: - Internals

    private func uploadBatches(_ synced: inout [[String: Any]]) {
        while true {
            let batch = storage.nextBatch(
                limit: config.maxBatchSize,
                orderDirection: config.locationsOrderDirection
            )
            guard !batch.isEmpty else { return }
            let payload = batch.map(\.json)
            guard executeRequest(body: buildBody(payload)) else { return }
            storage.deleteLocations(ids: batch.map(\.id))
            synced.append(contentsOf: payload)
            if config.maxBatchSize <= 0 { return } // single unbounded batch handled everything
        }
    }

    private func uploadOneByOne(_ synced: inout [[String: Any]]) {
        while true {
            guard let next = storage.nextBatch(
                limit: 1,
                orderDirection: config.locationsOrderDirection
            ).first else { return }
            guard executeRequest(body: buildBody(next.json)) else { return }
            storage.deleteLocations(ids: [next.id])
            synced.append(next.json)
        }
    }

    /// Wrap record(s) as `{ <httpRootProperty>: payload, ...params }` (or at
    /// root for `"."`), always including this installation's device identity so
    /// a shared server can attribute records without the host app doing
    /// anything. User-supplied `params` win on key collisions.
    private func buildBody(_ payload: Any) -> [String: Any] {
        var body: [String: Any] = [:]
        if config.httpRootProperty == ".", let record = payload as? [String: Any] {
            body = record
        } else {
            body[config.httpRootProperty] = payload
        }
        body["device_id"] = config.deviceId
        body["device_name"] = config.deviceName
        body["device"] = config.deviceDictionary()
        config.params.forEach { body[$0.key] = $0.value }
        return body
    }

    private func executeRequest(body: [String: Any]) -> Bool {
        guard
            let url = URL(string: config.url),
            let bodyData = try? JSONSerialization.data(withJSONObject: body)
        else {
            emitHttpEvent(success: false, status: 0, responseText: "Invalid url or body")
            return false
        }

        var request = URLRequest(url: url)
        request.httpMethod = config.httpMethod.uppercased()
        request.httpBody = bodyData
        request.timeoutInterval = config.httpTimeout / 1000
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        config.headers.forEach { key, value in
            request.setValue("\(value)", forHTTPHeaderField: key)
        }

        let semaphore = DispatchSemaphore(value: 0)
        var success = false
        var status = 0
        var responseText = ""

        URLSession.shared.dataTask(with: request) { data, response, error in
            if let httpResponse = response as? HTTPURLResponse {
                status = httpResponse.statusCode
                success = (200...299).contains(status)
            }
            if let data { responseText = String(data: data, encoding: .utf8) ?? "" }
            if let error { responseText = error.localizedDescription }
            semaphore.signal()
        }.resume()
        semaphore.wait()

        emitHttpEvent(success: success, status: status, responseText: responseText)
        return success
    }

    private func emitHttpEvent(success: Bool, status: Int, responseText: String) {
        onHttpEvent?([
            "success": success,
            "status": status,
            "responseText": responseText,
        ])
    }
}
