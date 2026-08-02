package com.openbackgroundgeolocation.geofence

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.openbackgroundgeolocation.data.DatabaseHelper
import com.openbackgroundgeolocation.event.EventDispatcher
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns geofence registration with the OS.
 *
 * Android caps concurrent geofences at 100 per app. When more are registered
 * with the SDK, proximity monitoring keeps only the nearest
 * [MAX_ACTIVE_GEOFENCES] within `geofenceProximityRadius` actively registered,
 * swapping as the device moves, and emits `geofenceschange` on each swap.
 */
class GeofenceManager(private val context: Context) {

    private val geofencingClient: GeofencingClient =
        LocationServices.getGeofencingClient(context)
    private val database: DatabaseHelper = DatabaseHelper.get(context)
    private var activeIdentifiers: Set<String> = emptySet()

    /** Last known containment per polygon geofence, for ENTER/EXIT edges. */
    private val polygonInsideState = mutableMapOf<String, Boolean>()

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    // ── Public CRUD (backed by SQLite so geofences survive reboots) ───────────

    fun add(geofenceJson: JSONObject) {
        val identifier = geofenceJson.getString("identifier")
        database.upsertGeofence(identifier, normalizePolygon(geofenceJson))
        refreshActiveRegistrations()
    }

    /**
     * Polygon geofences carry `vertices` instead of a centre and radius.
     * Neither platform can monitor a polygon natively, so derive the bounding
     * circle here: the OS wakes the device on that circle, and exact polygon
     * containment is decided by [PolygonGeometry.contains].
     */
    private fun normalizePolygon(geofenceJson: JSONObject): JSONObject {
        val vertices = PolygonGeometry.parseVertices(geofenceJson) ?: return geofenceJson
        val centre = PolygonGeometry.centroid(vertices)
        return JSONObject(geofenceJson.toString()).apply {
            put("latitude", centre.latitude)
            put("longitude", centre.longitude)
            put("radius", PolygonGeometry.boundingRadiusMeters(vertices, centre).toDouble())
            put("isPolygon", true)
        }
    }

    fun addAll(geofences: JSONArray) {
        for (index in 0 until geofences.length()) {
            val json = geofences.getJSONObject(index)
            database.upsertGeofence(json.getString("identifier"), normalizePolygon(json))
        }
        refreshActiveRegistrations()
    }

    /**
     * Identifiers of polygon geofences whose containment state changed for
     * this position, as `identifier to isInside`. Called on every recorded
     * location so polygon transitions are exact rather than circular.
     */
    fun evaluatePolygons(location: Location): List<Pair<String, Boolean>> {
        val transitions = mutableListOf<Pair<String, Boolean>>()
        for (json in database.allGeofences()) {
            if (!json.optBoolean("isPolygon", false)) continue
            val vertices = PolygonGeometry.parseVertices(json) ?: continue
            val identifier = json.getString("identifier")

            // Only test when plausibly near, so distant polygons cost nothing.
            val distance = PolygonGeometry.distanceToCentroidMeters(
                vertices, location.latitude, location.longitude
            )
            val boundingRadius =
                PolygonGeometry.boundingRadiusMeters(vertices, PolygonGeometry.centroid(vertices))
            if (distance > boundingRadius + location.accuracy) {
                if (polygonInsideState.remove(identifier) == true) {
                    transitions.add(identifier to false) // left the area entirely
                }
                continue
            }

            val isInside =
                PolygonGeometry.contains(vertices, location.latitude, location.longitude)
            val wasInside = polygonInsideState[identifier]
            if (wasInside != isInside) {
                polygonInsideState[identifier] = isInside
                // Suppress the very first observation when already outside —
                // that is not a transition, just initial state.
                if (wasInside != null || isInside) transitions.add(identifier to isInside)
            }
        }
        return transitions
    }

    /** `true` when the geofence with this identifier is a polygon. */
    fun isPolygon(identifier: String): Boolean =
        database.geofence(identifier)?.optBoolean("isPolygon", false) ?: false

    /** Point-in-polygon test for a stored polygon geofence. */
    fun polygonContains(identifier: String, location: Location): Boolean {
        val json = database.geofence(identifier) ?: return false
        val vertices = PolygonGeometry.parseVertices(json) ?: return false
        return PolygonGeometry.contains(vertices, location.latitude, location.longitude)
    }

    fun remove(identifier: String): Boolean {
        val removed = database.deleteGeofence(identifier)
        geofencingClient.removeGeofences(listOf(identifier))
        activeIdentifiers = activeIdentifiers - identifier
        return removed
    }

    fun removeAll() {
        database.deleteAllGeofences()
        if (activeIdentifiers.isNotEmpty()) {
            geofencingClient.removeGeofences(activeIdentifiers.toList())
        }
        activeIdentifiers = emptySet()
    }

    fun all(): List<JSONObject> = database.allGeofences()

    fun get(identifier: String): JSONObject? = database.geofence(identifier)

    fun exists(identifier: String): Boolean = database.geofence(identifier) != null

    // ── Monitoring lifecycle ──────────────────────────────────────────────────

    fun startMonitoring() {
        refreshActiveRegistrations()
    }

    fun stopMonitoring() {
        geofencingClient.removeGeofences(pendingIntent)
        activeIdentifiers = emptySet()
    }

    /**
     * Re-evaluate which geofences should be actively registered around
     * `location`. Called on every recorded location while tracking.
     */
    fun evaluateProximity(location: Location) {
        val configStore = com.openbackgroundgeolocation.config.ConfigStore.get(context)
        val registered = database.allGeofences()
        if (registered.size <= MAX_ACTIVE_GEOFENCES) return // everything already active

        val proximityRadius = configStore.geofenceProximityRadius
        val nearest = registered
            .map { json -> json to distanceMeters(location, json) }
            .filter { (_, distance) -> distance <= proximityRadius }
            .sortedBy { (_, distance) -> distance }
            .take(MAX_ACTIVE_GEOFENCES)
            .map { (json, _) -> json }

        val nearestIdentifiers = nearest.map { it.getString("identifier") }.toSet()
        if (nearestIdentifiers == activeIdentifiers) return

        val turnedOff = activeIdentifiers - nearestIdentifiers
        val turnedOn = nearest.filter { it.getString("identifier") !in activeIdentifiers }

        if (turnedOff.isNotEmpty()) geofencingClient.removeGeofences(turnedOff.toList())
        registerWithOs(nearest)
        activeIdentifiers = nearestIdentifiers

        EventDispatcher.emit(
            context,
            EventDispatcher.EVENT_GEOFENCESCHANGE,
            JSONObject()
                .put("on", JSONArray(turnedOn))
                .put("off", JSONArray(turnedOff.toList()))
        )
    }

    // ── Stationary geofence (motion-detection backup trigger) ─────────────────

    @SuppressLint("MissingPermission")
    fun registerStationaryGeofence(latitude: Double, longitude: Double, radius: Float) {
        val geofence = Geofence.Builder()
            .setRequestId(STATIONARY_IDENTIFIER)
            .setCircularRegion(latitude, longitude, radius.coerceAtLeast(MIN_OS_RADIUS))
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            .addGeofence(geofence)
            .setInitialTrigger(0)
            .build()
        geofencingClient.addGeofences(request, pendingIntent)
    }

    fun removeStationaryGeofence() {
        geofencingClient.removeGeofences(listOf(STATIONARY_IDENTIFIER))
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun refreshActiveRegistrations() {
        val configStore = com.openbackgroundgeolocation.config.ConfigStore.get(context)
        if (!configStore.enabled) return
        val registered = database.allGeofences()
        if (registered.isEmpty()) return
        val toActivate = registered.take(MAX_ACTIVE_GEOFENCES)
        registerWithOs(toActivate)
        activeIdentifiers = toActivate.map { it.getString("identifier") }.toSet()
    }

    @SuppressLint("MissingPermission")
    private fun registerWithOs(geofences: List<JSONObject>) {
        if (geofences.isEmpty()) return
        val configStore = com.openbackgroundgeolocation.config.ConfigStore.get(context)
        val osGeofences = geofences.map { json ->
            var transitions = 0
            if (json.optBoolean("notifyOnEntry", true)) {
                transitions = transitions or Geofence.GEOFENCE_TRANSITION_ENTER
            }
            if (json.optBoolean("notifyOnExit", false)) {
                transitions = transitions or Geofence.GEOFENCE_TRANSITION_EXIT
            }
            if (json.optBoolean("notifyOnDwell", false)) {
                transitions = transitions or Geofence.GEOFENCE_TRANSITION_DWELL
            }
            if (transitions == 0) transitions = Geofence.GEOFENCE_TRANSITION_ENTER

            Geofence.Builder()
                .setRequestId(json.getString("identifier"))
                .setCircularRegion(
                    json.getDouble("latitude"),
                    json.getDouble("longitude"),
                    json.getDouble("radius").toFloat().coerceAtLeast(MIN_OS_RADIUS)
                )
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(transitions)
                .setLoiteringDelay(json.optInt("loiteringDelay", 0))
                .build()
        }

        val initialTrigger =
            if (configStore.geofenceInitialTriggerEntry) GeofencingRequest.INITIAL_TRIGGER_ENTER
            else 0
        val request = GeofencingRequest.Builder()
            .addGeofences(osGeofences)
            .setInitialTrigger(initialTrigger)
            .build()
        geofencingClient.addGeofences(request, pendingIntent)
    }

    private fun distanceMeters(from: Location, geofenceJson: JSONObject): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            from.latitude,
            from.longitude,
            geofenceJson.getDouble("latitude"),
            geofenceJson.getDouble("longitude"),
            results
        )
        return results[0]
    }

    companion object {
        const val STATIONARY_IDENTIFIER = "__open_bg_geo_stationary__"
        /** Android allows 100 per app; one slot is reserved for the stationary geofence. */
        const val MAX_ACTIVE_GEOFENCES = 98
        private const val MIN_OS_RADIUS = 100f
        private const val REQUEST_CODE = 9943
    }
}
