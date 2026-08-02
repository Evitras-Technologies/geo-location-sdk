package com.openbackgroundgeolocation.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Configuration clamping. An SDK cannot trust its callers — these rules are
 * what stop a bad value from spinning location services and destroying the
 * user's battery, so each boundary is pinned down explicitly.
 */
class ConfigValidatorTest {

    private fun sanitized(vararg pairs: Pair<String, Any>): JSONObject {
        val config = JSONObject()
        pairs.forEach { (key, value) -> config.put(key, value) }
        return ConfigValidator.sanitize(config)
    }

    // ── Numeric clamping ──────────────────────────────────────────────────────

    @Test
    fun `negative distanceFilter is clamped to zero`() {
        assertEquals(0.0, sanitized("distanceFilter" to -50).optDouble("distanceFilter"), 1e-9)
    }

    @Test
    fun `distanceFilter of zero survives for realtime mode`() {
        assertEquals(0.0, sanitized("distanceFilter" to 0).optDouble("distanceFilter"), 1e-9)
    }

    @Test
    fun `stationaryRadius below the platform floor is raised to 25`() {
        assertEquals(25.0, sanitized("stationaryRadius" to 5).optDouble("stationaryRadius"), 1e-9)
    }

    @Test
    fun `locationUpdateInterval below one second is raised`() {
        assertEquals(1_000L, sanitized("locationUpdateInterval" to 10).optLong("locationUpdateInterval"))
    }

    @Test
    fun `absurdly large interval is capped at one hour`() {
        assertEquals(
            3_600_000L,
            sanitized("locationUpdateInterval" to 999_999_999L).optLong("locationUpdateInterval")
        )
    }

    @Test
    fun `activity confidence is constrained to a percentage`() {
        assertEquals(100, sanitized("minimumActivityRecognitionConfidence" to 5_000).optInt("minimumActivityRecognitionConfidence"))
        assertEquals(0, sanitized("minimumActivityRecognitionConfidence" to -20).optInt("minimumActivityRecognitionConfidence"))
    }

    @Test
    fun `string NaN falls back to the minimum rather than propagating`() {
        // org.json rejects a literal NaN double outright, but a *string*
        // "NaN" crossing the JS bridge parses to NaN — that is the case the
        // guard exists for, and an unguarded NaN would poison every
        // distance comparison in the tracking engine.
        assertEquals(0.0, sanitized("distanceFilter" to "NaN").optDouble("distanceFilter"), 1e-9)
    }

    @Test
    fun `non-numeric string falls back to the minimum`() {
        assertEquals(0.0, sanitized("distanceFilter" to "abc").optDouble("distanceFilter"), 1e-9)
    }

    @Test
    fun `untouched keys are left absent`() {
        assertFalse(sanitized("distanceFilter" to 10).has("stopTimeout"))
    }

    // ── Interval floors with a disable value ──────────────────────────────────

    @Test
    fun `heartbeatInterval of zero stays disabled`() {
        assertEquals(0, sanitized("heartbeatInterval" to 0).optInt("heartbeatInterval"))
    }

    @Test
    fun `heartbeatInterval below the 60 second floor is raised`() {
        assertEquals(60, sanitized("heartbeatInterval" to 5).optInt("heartbeatInterval"))
    }

    @Test
    fun `stationaryUpdateInterval of zero stays disabled`() {
        assertEquals(0, sanitized("stationaryUpdateInterval" to 0).optInt("stationaryUpdateInterval"))
    }

    @Test
    fun `stationaryUpdateInterval below five seconds is raised`() {
        assertEquals(5, sanitized("stationaryUpdateInterval" to 1).optInt("stationaryUpdateInterval"))
    }

    @Test
    fun `stationaryUpdateInterval of twelve seconds is honoured`() {
        assertEquals(12, sanitized("stationaryUpdateInterval" to 12).optInt("stationaryUpdateInterval"))
    }

    // ── HTTP settings ─────────────────────────────────────────────────────────

    @Test
    fun `unsupported http method falls back to POST`() {
        assertEquals("POST", sanitized("method" to "DELETE").optString("method"))
    }

    @Test
    fun `PUT is accepted`() {
        assertEquals("PUT", sanitized("method" to "PUT").optString("method"))
    }

    @Test
    fun `empty httpRootProperty falls back to location`() {
        assertEquals("location", sanitized("httpRootProperty" to "").optString("httpRootProperty"))
    }

    @Test
    fun `dot httpRootProperty is preserved for root-level bodies`() {
        assertEquals(".", sanitized("httpRootProperty" to ".").optString("httpRootProperty"))
    }

    // ── Accuracy filter ───────────────────────────────────────────────────────

    @Test
    fun `minimumAccuracy of zero means keep everything`() {
        assertEquals(0.0, sanitized("minimumAccuracy" to 0).optDouble("minimumAccuracy"), 1e-9)
    }

    @Test
    fun `negative minimumAccuracy is clamped to zero`() {
        assertEquals(0.0, sanitized("minimumAccuracy" to -10).optDouble("minimumAccuracy"), 1e-9)
    }
}
