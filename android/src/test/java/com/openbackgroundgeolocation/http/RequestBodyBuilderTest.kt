package com.openbackgroundgeolocation.http

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload body is the SDK's contract with the customer's server, so its
 * exact shape is pinned down here.
 */
class RequestBodyBuilderTest {

    private val device = JSONObject()
        .put("id", "device-uuid-1")
        .put("name", "samsung SM-S711B")
        .put("model", "SM-S711B")
        .put("manufacturer", "samsung")
        .put("platform", "android")
        .put("os_version", "14")

    private fun record(latitude: Double = 12.9716) = JSONObject()
        .put("uuid", "rec-1")
        .put("timestamp", "2026-08-01T10:00:00.000Z")
        .put("coords", JSONObject().put("latitude", latitude).put("longitude", 77.5946))

    // ── Root property ─────────────────────────────────────────────────────────

    @Test
    fun `single record is nested under the root property`() {
        val body = RequestBodyBuilder.build(record(), "location", device, JSONObject())
        assertTrue(body.has("location"))
        assertEquals("rec-1", body.getJSONObject("location").getString("uuid"))
    }

    @Test
    fun `custom root property is honoured`() {
        val body = RequestBodyBuilder.build(record(), "payload", device, JSONObject())
        assertTrue(body.has("payload"))
        assertFalse(body.has("location"))
    }

    @Test
    fun `dot root property flattens the record to the body root`() {
        val body = RequestBodyBuilder.build(record(), ".", device, JSONObject())
        assertEquals("rec-1", body.getString("uuid"))
        assertFalse(body.has("location"))
    }

    @Test
    fun `batch array is nested under the root property`() {
        val batch = JSONArray().put(record(12.97)).put(record(12.98))
        val body = RequestBodyBuilder.build(batch, "location", device, JSONObject())
        assertEquals(2, body.getJSONArray("location").length())
    }

    // ── Device identity ───────────────────────────────────────────────────────

    @Test
    fun `device identity is always attached`() {
        val body = RequestBodyBuilder.build(record(), "location", device, JSONObject())
        assertEquals("device-uuid-1", body.getString("device_id"))
        assertEquals("samsung SM-S711B", body.getString("device_name"))
        assertEquals("android", body.getJSONObject("device").getString("platform"))
    }

    @Test
    fun `device identity survives the flattened root form`() {
        val body = RequestBodyBuilder.build(record(), ".", device, JSONObject())
        assertEquals("device-uuid-1", body.getString("device_id"))
    }

    // ── params ────────────────────────────────────────────────────────────────

    @Test
    fun `params are merged into the body root`() {
        val params = JSONObject().put("company_id", 42).put("driver", "driver-001")
        val body = RequestBodyBuilder.build(record(), "location", device, params)
        assertEquals(42, body.getInt("company_id"))
        assertEquals("driver-001", body.getString("driver"))
        assertTrue(body.has("location"))
    }

    @Test
    fun `params win over SDK keys on collision`() {
        // Callers must be able to override the SDK's own fields.
        val params = JSONObject().put("device_id", "caller-supplied")
        val body = RequestBodyBuilder.build(record(), "location", device, params)
        assertEquals("caller-supplied", body.getString("device_id"))
    }

    // ── Token refresh payload ─────────────────────────────────────────────────

    @Test
    fun `default refresh payload uses the conventional field name`() {
        val payload = RequestBodyBuilder.buildRefreshPayload(null, "refresh-abc")
        assertEquals("refresh-abc", payload.getString("refresh_token"))
    }

    @Test
    fun `refresh token placeholder is substituted`() {
        val template = JSONObject()
            .put("token", RequestBodyBuilder.REFRESH_TOKEN_PLACEHOLDER)
            .put("grant_type", "refresh_token")
        val payload = RequestBodyBuilder.buildRefreshPayload(template, "refresh-xyz")
        assertEquals("refresh-xyz", payload.getString("token"))
        assertEquals("refresh_token", payload.getString("grant_type")) // literal preserved
    }

    // ── A record in the host's own shape (locationTemplate) ───────────────────

    private val presenceTemplate = """{"lat":<%= latitude %>,"lng":<%= longitude %>}"""

    @Test
    fun `a template with params is the whole body`() {
        val params = JSONObject().put("online", true).put("vehicleId", "65")
        val body = RequestBodyBuilder.buildFromTemplate(record(), presenceTemplate, ".", params)

        assertEquals(12.9716, body.getDouble("lat"), 0.0)
        assertEquals(77.5946, body.getDouble("lng"), 0.0)
        assertTrue(body.getBoolean("online"))
        assertEquals("65", body.getString("vehicleId"))
        // Nothing the host did not ask for: a strict server refuses the rest.
        assertEquals(4, body.length())
        assertFalse(body.has("device_id"))
        assertFalse(body.has("coords"))
    }

    @Test
    fun `a templated record can sit under a root property`() {
        val body = RequestBodyBuilder.buildFromTemplate(record(), presenceTemplate, "position", JSONObject())
        assertEquals(12.9716, body.getJSONObject("position").getDouble("lat"), 0.0)
        assertEquals(1, body.length())
    }

    @Test
    fun `every record of a batch is shaped`() {
        val batch = JSONArray().put(record(1.0)).put(record(2.0))
        val body = RequestBodyBuilder.buildFromTemplate(batch, presenceTemplate, "positions", JSONObject())
        val positions = body.getJSONArray("positions")
        assertEquals(2, positions.length())
        assertEquals(2.0, positions.getJSONObject(1).getDouble("lat"), 0.0)
        assertFalse(positions.getJSONObject(0).has("uuid"))
    }

    @Test
    fun `text is escaped and left for the template to quote`() {
        val odd = record().put("event", "say \"moving\"")
        val shaped = RequestBodyBuilder.render("""{"at":"<%= timestamp %>","why":"<%= event %>"}""", odd)!!
        assertEquals("2026-08-01T10:00:00.000Z", shaped.getString("at"))
        assertEquals("say \"moving\"", shaped.getString("why"))
    }

    @Test
    fun `a dotted tag follows a path and a missing value is null`() {
        val charged = record().put("battery", JSONObject().put("level", 0.5))
        val shaped = RequestBodyBuilder.render(
            """{"level":<%= battery.level %>,"speed":<%= speed %>,"gone":<%= no.such %>}""",
            charged
        )!!
        assertEquals(0.5, shaped.getDouble("level"), 0.0)
        assertTrue(shaped.isNull("speed"))
        assertTrue(shaped.isNull("gone"))
    }

    @Test
    fun `a template that is not a JSON object leaves the record as it was`() {
        val body = RequestBodyBuilder.buildFromTemplate(record(), "lat=<%= latitude %>", "location", JSONObject())
        assertEquals("rec-1", body.getJSONObject("location").getString("uuid"))
    }
}
