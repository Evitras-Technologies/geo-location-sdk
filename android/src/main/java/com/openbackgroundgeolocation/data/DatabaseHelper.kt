package com.openbackgroundgeolocation.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** A persisted, not-yet-synced location row. */
data class LocationRow(val id: Long, val uuid: String, val json: JSONObject)

/**
 * SQLite persistence for recorded locations, registered geofences and the
 * SDK's own rolling log.
 */
class DatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                recorded_at INTEGER NOT NULL,
                json TEXT NOT NULL,
                locked INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE geofences (
                identifier TEXT PRIMARY KEY,
                json TEXT NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                logged_at INTEGER NOT NULL,
                level TEXT NOT NULL,
                message TEXT NOT NULL
            )"""
        )
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createIndexes(db)
    }

    /**
     * Every upload query orders by `recorded_at`; without an index SQLite full-
     * scans and sorts the whole table on each one, which is O(n) per recorded
     * location. Same for log pruning.
     */
    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_locations_recorded_at ON locations (recorded_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_logged_at ON logs (logged_at)")
    }

    // ── Locations ─────────────────────────────────────────────────────────────

    @Synchronized
    fun insertLocation(uuid: String, recordedAtMillis: Long, json: JSONObject) {
        writableDatabase.insertWithOnConflict(
            "locations",
            null,
            ContentValues().apply {
                put("uuid", uuid)
                put("recorded_at", recordedAtMillis)
                put("json", json.toString())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun allLocations(orderDirection: String): List<LocationRow> =
        queryLocations("SELECT id, uuid, json FROM locations ORDER BY recorded_at ${sanitizeOrder(orderDirection)}")

    @Synchronized
    fun nextBatch(limit: Int, orderDirection: String): List<LocationRow> {
        val limitClause = if (limit > 0) " LIMIT $limit" else ""
        return queryLocations(
            "SELECT id, uuid, json FROM locations WHERE locked = 0 ORDER BY recorded_at ${sanitizeOrder(orderDirection)}$limitClause"
        )
    }

    @Synchronized
    fun locationCount(): Long {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM locations", null).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    @Synchronized
    fun deleteLocations(ids: List<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL("DELETE FROM locations WHERE id IN (${ids.joinToString(",")})")
    }

    @Synchronized
    fun deleteLocationByUuid(uuid: String): Boolean =
        writableDatabase.delete("locations", "uuid = ?", arrayOf(uuid)) > 0

    @Synchronized
    fun deleteAllLocations() {
        writableDatabase.delete("locations", null, null)
    }

    /** Enforce the configured retention rules; oldest rows are pruned first. */
    @Synchronized
    fun pruneLocations(maxDays: Int, maxRecords: Int) {
        val db = writableDatabase
        if (maxDays > 0) {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(maxDays.toLong())
            db.delete("locations", "recorded_at < ?", arrayOf(cutoff.toString()))
        }
        if (maxRecords > 0) {
            db.execSQL(
                """DELETE FROM locations WHERE id NOT IN (
                    SELECT id FROM locations ORDER BY recorded_at DESC LIMIT $maxRecords
                )"""
            )
        }
    }

    private fun queryLocations(sql: String): List<LocationRow> {
        val rows = mutableListOf<LocationRow>()
        readableDatabase.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching {
                    rows.add(
                        LocationRow(
                            id = cursor.getLong(0),
                            uuid = cursor.getString(1),
                            json = JSONObject(cursor.getString(2))
                        )
                    )
                }
            }
        }
        return rows
    }

    private fun sanitizeOrder(direction: String): String =
        if (direction.equals("DESC", ignoreCase = true)) "DESC" else "ASC"

    // ── Geofences ─────────────────────────────────────────────────────────────

    @Synchronized
    fun upsertGeofence(identifier: String, json: JSONObject) {
        writableDatabase.insertWithOnConflict(
            "geofences",
            null,
            ContentValues().apply {
                put("identifier", identifier)
                put("json", json.toString())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun geofence(identifier: String): JSONObject? {
        readableDatabase.rawQuery(
            "SELECT json FROM geofences WHERE identifier = ?",
            arrayOf(identifier)
        ).use { cursor ->
            return if (cursor.moveToFirst()) runCatching { JSONObject(cursor.getString(0)) }.getOrNull() else null
        }
    }

    @Synchronized
    fun allGeofences(): List<JSONObject> {
        val rows = mutableListOf<JSONObject>()
        readableDatabase.rawQuery("SELECT json FROM geofences", null).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { rows.add(JSONObject(cursor.getString(0))) }
            }
        }
        return rows
    }

    @Synchronized
    fun deleteGeofence(identifier: String): Boolean =
        writableDatabase.delete("geofences", "identifier = ?", arrayOf(identifier)) > 0

    @Synchronized
    fun deleteAllGeofences() {
        writableDatabase.delete("geofences", null, null)
    }

    // ── Logs ──────────────────────────────────────────────────────────────────

    @Synchronized
    fun appendLog(level: String, message: String) {
        writableDatabase.insert(
            "logs",
            null,
            ContentValues().apply {
                put("logged_at", System.currentTimeMillis())
                put("level", level)
                put("message", message)
            }
        )
    }

    @Synchronized
    fun readLog(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        val builder = StringBuilder()
        readableDatabase.rawQuery(
            "SELECT logged_at, level, message FROM logs ORDER BY logged_at ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                builder
                    .append(formatter.format(Date(cursor.getLong(0))))
                    .append(" [").append(cursor.getString(1).uppercase(Locale.US)).append("] ")
                    .append(cursor.getString(2))
                    .append('\n')
            }
        }
        return builder.toString()
    }

    @Synchronized
    fun destroyLog() {
        writableDatabase.delete("logs", null, null)
    }

    @Synchronized
    fun pruneLog(maxDays: Int) {
        if (maxDays <= 0) return
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(maxDays.toLong())
        writableDatabase.delete("logs", "logged_at < ?", arrayOf(cutoff.toString()))
    }

    companion object {
        private const val DATABASE_NAME = "open_bg_geo.db"
        private const val DATABASE_VERSION = 2

        @Volatile
        private var instance: DatabaseHelper? = null

        fun get(context: Context): DatabaseHelper =
            instance ?: synchronized(this) {
                instance ?: DatabaseHelper(context.applicationContext).also { instance = it }
            }
    }
}
