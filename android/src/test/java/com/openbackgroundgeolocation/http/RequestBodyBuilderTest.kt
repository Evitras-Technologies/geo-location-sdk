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
}
