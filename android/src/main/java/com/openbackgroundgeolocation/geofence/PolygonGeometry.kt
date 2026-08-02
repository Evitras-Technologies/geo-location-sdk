package com.openbackgroundgeolocation.geofence

import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos

/** A polygon vertex in decimal degrees. */
data class Vertex(val latitude: Double, val longitude: Double)

/**
 * Geometry for polygon geofences — arbitrary shapes such as parks, building
 * footprints or delivery zones.
 *
 * Neither Android nor iOS can monitor polygons natively; both only accept
 * circles. The SDK therefore registers the polygon's **bounding circle** with
 * the OS so the device is still woken when it approaches, then decides true
 * containment itself with a point-in-polygon test. That keeps the wake-up
 * behaviour of native geofencing while giving exact polygon semantics.
 */
object PolygonGeometry {

    fun parseVertices(json: JSONObject): List<Vertex>? {
        val array = json.optJSONArray("vertices") ?: return null
        if (array.length() < 3) return null // fewer than 3 points is not a polygon

        val vertices = mutableListOf<Vertex>()
        for (index in 0 until array.length()) {
            when (val entry = array.get(index)) {
                // [latitude, longitude]
                is JSONArray -> {
                    if (entry.length() < 2) return null
                    vertices.add(Vertex(entry.getDouble(0), entry.getDouble(1)))
                }
                // { "latitude": …, "longitude": … }
                is JSONObject -> vertices.add(
                    Vertex(entry.getDouble("latitude"), entry.getDouble("longitude"))
                )
                else -> return null
            }
        }
        return vertices
    }

    fun isPolygon(json: JSONObject): Boolean = (json.optJSONArray("vertices")?.length() ?: 0) >= 3

    /**
     * Ray-casting containment test. Counts how many polygon edges a ray cast
     * east from the point crosses: an odd count means inside. Handles concave
     * shapes and holes-free polygons of any vertex count.
     */
    fun contains(vertices: List<Vertex>, latitude: Double, longitude: Double): Boolean {
        var inside = false
        var j = vertices.size - 1
        for (i in vertices.indices) {
            val vertexI = vertices[i]
            val vertexJ = vertices[j]
            val straddlesRay = (vertexI.latitude > latitude) != (vertexJ.latitude > latitude)
            if (straddlesRay) {
                val intersectionLongitude = vertexJ.longitude +
                    (latitude - vertexJ.latitude) /
                    (vertexI.latitude - vertexJ.latitude) *
                    (vertexI.longitude - vertexJ.longitude)
                if (longitude < intersectionLongitude) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Arithmetic centre of the vertices — the bounding circle's centre. */
    fun centroid(vertices: List<Vertex>): Vertex {
        val latitude = vertices.sumOf { it.latitude } / vertices.size
        val longitude = vertices.sumOf { it.longitude } / vertices.size
        return Vertex(latitude, longitude)
    }

    /** Radius in metres covering every vertex, plus slack so entry is never missed. */
    fun boundingRadiusMeters(vertices: List<Vertex>, centre: Vertex): Float {
        val results = FloatArray(1)
        var maximum = 0f
        for (vertex in vertices) {
            Location.distanceBetween(
                centre.latitude, centre.longitude,
                vertex.latitude, vertex.longitude,
                results
            )
            if (results[0] > maximum) maximum = results[0]
        }
        return maximum + BOUNDING_SLACK_METERS
    }

    /**
     * Approximate distance in metres from a point to the polygon's edge.
     * Used to decide how close the device must be before polygon containment
     * is re-evaluated on every location update.
     */
    fun distanceToCentroidMeters(vertices: List<Vertex>, latitude: Double, longitude: Double): Float {
        val centre = centroid(vertices)
        val results = FloatArray(1)
        Location.distanceBetween(centre.latitude, centre.longitude, latitude, longitude, results)
        return results[0]
    }

    /** Rough area in square metres (shoelace formula on an equirectangular projection). */
    fun areaSquareMeters(vertices: List<Vertex>): Double {
        if (vertices.size < 3) return 0.0
        val metresPerDegreeLat = 111_320.0
        val referenceLatitude = centroid(vertices).latitude
        val metresPerDegreeLon = metresPerDegreeLat * cos(Math.toRadians(referenceLatitude))

        var sum = 0.0
        var j = vertices.size - 1
        for (i in vertices.indices) {
            val xi = vertices[i].longitude * metresPerDegreeLon
            val yi = vertices[i].latitude * metresPerDegreeLat
            val xj = vertices[j].longitude * metresPerDegreeLon
            val yj = vertices[j].latitude * metresPerDegreeLat
            sum += (xj + xi) * (yj - yi)
            j = i
        }
        return kotlin.math.abs(sum / 2.0)
    }

    /** Play Services enforces a minimum usable geofence radius. */
    private const val BOUNDING_SLACK_METERS = 50f
}
