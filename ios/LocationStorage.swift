import Foundation
import SQLite3

/// A persisted, not-yet-synced location row.
struct LocationRow {
    let id: Int64
    let uuid: String
    let json: [String: Any]
}

/// SQLite persistence for recorded locations, registered geofences and the
/// SDK's rolling log. All access is serialized on an internal queue.
final class LocationStorage {

    static let shared = LocationStorage()

    private var database: OpaquePointer?
    private let queue = DispatchQueue(label: "open-bg-geo.storage")
    private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private init() {
        let databaseURL = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("open_bg_geo.sqlite")
        try? FileManager.default.createDirectory(
            at: databaseURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        if sqlite3_open(databaseURL.path, &database) == SQLITE_OK {
            createTables()
        }
    }

    private func createTables() {
        let statements = [
            """
            CREATE TABLE IF NOT EXISTS locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                recorded_at REAL NOT NULL,
                json TEXT NOT NULL
            )
            """,
            "CREATE TABLE IF NOT EXISTS geofences (identifier TEXT PRIMARY KEY, json TEXT NOT NULL)",
            """
            CREATE TABLE IF NOT EXISTS logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                logged_at REAL NOT NULL,
                level TEXT NOT NULL,
                message TEXT NOT NULL
            )
            """,
        ]
        statements.forEach { sqlite3_exec(database, $0, nil, nil, nil) }
    }

    // MARK: - Locations

    func insertLocation(uuid: String, json: [String: Any]) {
        guard let serialized = serialize(json) else { return }
        queue.sync {
            run(
                "INSERT OR REPLACE INTO locations (uuid, recorded_at, json) VALUES (?, ?, ?)",
                bindings: [.text(uuid), .real(Date().timeIntervalSince1970), .text(serialized)]
            )
        }
    }

    func allLocations(orderDirection: String) -> [LocationRow] {
        let order = orderDirection.uppercased() == "DESC" ? "DESC" : "ASC"
        return queue.sync {
            queryRows("SELECT id, uuid, json FROM locations ORDER BY recorded_at \(order)")
        }
    }

    func nextBatch(limit: Int, orderDirection: String) -> [LocationRow] {
        let order = orderDirection.uppercased() == "DESC" ? "DESC" : "ASC"
        let limitClause = limit > 0 ? " LIMIT \(limit)" : ""
        return queue.sync {
            queryRows("SELECT id, uuid, json FROM locations ORDER BY recorded_at \(order)\(limitClause)")
        }
    }

    func locationCount() -> Int {
        queue.sync { queryScalar("SELECT COUNT(*) FROM locations") }
    }

    func deleteLocations(ids: [Int64]) {
        guard !ids.isEmpty else { return }
        let list = ids.map(String.init).joined(separator: ",")
        queue.sync { run("DELETE FROM locations WHERE id IN (\(list))", bindings: []) }
    }

    func deleteLocation(uuid: String) -> Bool {
        queue.sync {
            run("DELETE FROM locations WHERE uuid = ?", bindings: [.text(uuid)])
            return sqlite3_changes(database) > 0
        }
    }

    func deleteAllLocations() {
        queue.sync { run("DELETE FROM locations", bindings: []) }
    }

    /// Enforce the configured retention rules; oldest rows are pruned first.
    func pruneLocations(maxDays: Int, maxRecords: Int) {
        queue.sync {
            if maxDays > 0 {
                let cutoff = Date().timeIntervalSince1970 - Double(maxDays) * 86_400
                run("DELETE FROM locations WHERE recorded_at < ?", bindings: [.real(cutoff)])
            }
            if maxRecords > 0 {
                run(
                    """
                    DELETE FROM locations WHERE id NOT IN (
                        SELECT id FROM locations ORDER BY recorded_at DESC LIMIT \(maxRecords)
                    )
                    """,
                    bindings: []
                )
            }
        }
    }

    // MARK: - Geofences

    func upsertGeofence(identifier: String, json: [String: Any]) {
        guard let serialized = serialize(json) else { return }
        queue.sync {
            run(
                "INSERT OR REPLACE INTO geofences (identifier, json) VALUES (?, ?)",
                bindings: [.text(identifier), .text(serialized)]
            )
        }
    }

    func geofence(identifier: String) -> [String: Any]? {
        queue.sync {
            queryJsonColumn(
                "SELECT json FROM geofences WHERE identifier = ?",
                bindings: [.text(identifier)]
            ).first
        }
    }

    func allGeofences() -> [[String: Any]] {
        queue.sync { queryJsonColumn("SELECT json FROM geofences", bindings: []) }
    }

    func deleteGeofence(identifier: String) -> Bool {
        queue.sync {
            run("DELETE FROM geofences WHERE identifier = ?", bindings: [.text(identifier)])
            return sqlite3_changes(database) > 0
        }
    }

    func deleteAllGeofences() {
        queue.sync { run("DELETE FROM geofences", bindings: []) }
    }

    // MARK: - Logs

    func appendLog(level: String, message: String) {
        queue.sync {
            run(
                "INSERT INTO logs (logged_at, level, message) VALUES (?, ?, ?)",
                bindings: [.real(Date().timeIntervalSince1970), .text(level), .text(message)]
            )
        }
    }

    func readLog() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        var lines: [String] = []
        queue.sync {
            var statement: OpaquePointer?
            guard sqlite3_prepare_v2(
                database,
                "SELECT logged_at, level, message FROM logs ORDER BY logged_at ASC",
                -1, &statement, nil
            ) == SQLITE_OK else { return }
            defer { sqlite3_finalize(statement) }
            while sqlite3_step(statement) == SQLITE_ROW {
                let loggedAt = Date(timeIntervalSince1970: sqlite3_column_double(statement, 0))
                let level = String(cString: sqlite3_column_text(statement, 1))
                let message = String(cString: sqlite3_column_text(statement, 2))
                lines.append("\(formatter.string(from: loggedAt)) [\(level.uppercased())] \(message)")
            }
        }
        return lines.joined(separator: "\n")
    }

    func destroyLog() {
        queue.sync { run("DELETE FROM logs", bindings: []) }
    }

    func pruneLog(maxDays: Int) {
        guard maxDays > 0 else { return }
        let cutoff = Date().timeIntervalSince1970 - Double(maxDays) * 86_400
        queue.sync { run("DELETE FROM logs WHERE logged_at < ?", bindings: [.real(cutoff)]) }
    }

    // MARK: - SQLite plumbing

    private enum Binding {
        case text(String)
        case real(Double)
    }

    private func run(_ sql: String, bindings: [Binding]) {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(statement) }
        bind(statement, bindings)
        sqlite3_step(statement)
    }

    private func bind(_ statement: OpaquePointer?, _ bindings: [Binding]) {
        for (index, binding) in bindings.enumerated() {
            let position = Int32(index + 1)
            switch binding {
            case .text(let value):
                sqlite3_bind_text(statement, position, value, -1, SQLITE_TRANSIENT)
            case .real(let value):
                sqlite3_bind_double(statement, position, value)
            }
        }
    }

    private func queryRows(_ sql: String) -> [LocationRow] {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(statement) }
        var rows: [LocationRow] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            let id = sqlite3_column_int64(statement, 0)
            let uuid = String(cString: sqlite3_column_text(statement, 1))
            let jsonText = String(cString: sqlite3_column_text(statement, 2))
            if let json = deserialize(jsonText) {
                rows.append(LocationRow(id: id, uuid: uuid, json: json))
            }
        }
        return rows
    }

    private func queryJsonColumn(_ sql: String, bindings: [Binding]) -> [[String: Any]] {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(statement) }
        bind(statement, bindings)
        var results: [[String: Any]] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            let jsonText = String(cString: sqlite3_column_text(statement, 0))
            if let json = deserialize(jsonText) {
                results.append(json)
            }
        }
        return results
    }

    private func queryScalar(_ sql: String) -> Int {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else { return 0 }
        defer { sqlite3_finalize(statement) }
        return sqlite3_step(statement) == SQLITE_ROW ? Int(sqlite3_column_int64(statement, 0)) : 0
    }

    private func serialize(_ json: [String: Any]) -> String? {
        guard let data = try? JSONSerialization.data(withJSONObject: json) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func deserialize(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }
}
