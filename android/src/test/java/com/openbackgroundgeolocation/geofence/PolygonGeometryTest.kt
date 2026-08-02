package com.openbackgroundgeolocation.geofence

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Polygon geofence geometry. These cover the pure logic — containment,
 * parsing, centroid, area — which is what decides whether a device is inside
 * a delivery zone, so it must be exactly right.
 */
class PolygonGeometryTest {

    /** A 0.01° square around (12.97, 77.59) — roughly 1.1 km per side. */
    private val square = listOf(
        Vertex(12.970, 77.590),
        Vertex(12.980, 77.590),
        Vertex(12.980, 77.600),
        Vertex(12.970, 77.600)
    )

    /** An L-shape, to prove concave polygons work. */
    private val concaveL = listOf(
        Vertex(0.0, 0.0),
        Vertex(0.0, 10.0),
        Vertex(5.0, 10.0),
        Vertex(5.0, 5.0),
        Vertex(10.0, 5.0),
        Vertex(10.0, 0.0)
    )

    // ── Containment ───────────────────────────────────────────────────────────

    @Test
    fun `point at the centre is inside`() {
        assertTrue(PolygonGeometry.contains(square, 12.975, 77.595))
    }

    @Test
    fun `point outside every edge is outside`() {
        assertFalse(PolygonGeometry.contains(square, 12.960, 77.595)) // south
        assertFalse(PolygonGeometry.contains(square, 12.990, 77.595)) // north
        assertFalse(PolygonGeometry.contains(square, 12.975, 77.580)) // west
        assertFalse(PolygonGeometry.contains(square, 12.975, 77.610)) // east
    }

    @Test
    fun `point just inside an edge is inside`() {
        assertTrue(PolygonGeometry.contains(square, 12.9701, 77.5951))
    }

    @Test
    fun `point just outside an edge is outside`() {
        assertFalse(PolygonGeometry.contains(square, 12.9699, 77.5951))
    }

    @Test
    fun `concave polygon excludes the notch`() {
        // Inside the arms of the L.
        assertTrue(PolygonGeometry.contains(concaveL, 2.0, 2.0))
        assertTrue(PolygonGeometry.contains(concaveL, 7.0, 2.0))
        assertTrue(PolygonGeometry.contains(concaveL, 2.0, 8.0))
        // Inside the bounding box but in the missing quadrant — must be outside.
        assertFalse(PolygonGeometry.contains(concaveL, 8.0, 8.0))
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    @Test
    fun `parses array pair vertices`() {
        val json = JSONObject().put(
            "vertices",
            JSONArray().apply {
                put(JSONArray(listOf(12.970, 77.590)))
                put(JSONArray(listOf(12.980, 77.590)))
                put(JSONArray(listOf(12.980, 77.600)))
            }
        )
        val vertices = PolygonGeometry.parseVertices(json)
        assertEquals(3, vertices?.size)
        assertEquals(12.970, vertices!![0].latitude, 1e-9)
        assertEquals(77.590, vertices[0].longitude, 1e-9)
    }

    @Test
    fun `parses object vertices`() {
        val json = JSONObject().put(
            "vertices",
            JSONArray().apply {
                put(JSONObject().put("latitude", 1.0).put("longitude", 2.0))
                put(JSONObject().put("latitude", 3.0).put("longitude", 4.0))
                put(JSONObject().put("latitude", 5.0).put("longitude", 6.0))
            }
        )
        val vertices = PolygonGeometry.parseVertices(json)
        assertEquals(3, vertices?.size)
        assertEquals(5.0, vertices!![2].latitude, 1e-9)
    }

    @Test
    fun `rejects fewer than three vertices`() {
        val json = JSONObject().put(
            "vertices",
            JSONArray().apply {
                put(JSONArray(listOf(1.0, 2.0)))
                put(JSONArray(listOf(3.0, 4.0)))
            }
        )
        assertNull(PolygonGeometry.parseVertices(json))
        assertFalse(PolygonGeometry.isPolygon(json))
    }

    @Test
    fun `circular geofence is not a polygon`() {
        val json = JSONObject()
            .put("identifier", "depot")
            .put("latitude", 12.97)
            .put("longitude", 77.59)
            .put("radius", 200)
        assertFalse(PolygonGeometry.isPolygon(json))
        assertNull(PolygonGeometry.parseVertices(json))
    }

    // ── Centroid & area ───────────────────────────────────────────────────────

    @Test
    fun `centroid of a square is its middle`() {
        val centre = PolygonGeometry.centroid(square)
        assertEquals(12.975, centre.latitude, 1e-9)
        assertEquals(77.595, centre.longitude, 1e-9)
    }

    @Test
    fun `area of the square is about one point two square kilometres`() {
        // 0.01° latitude ≈ 1113 m; 0.01° longitude at 12.97°N ≈ 1085 m.
        val area = PolygonGeometry.areaSquareMeters(square)
        assertTrue("area was $area", area > 1_100_000 && area < 1_300_000)
    }
}
