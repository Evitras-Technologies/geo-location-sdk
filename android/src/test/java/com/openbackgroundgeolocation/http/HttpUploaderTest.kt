package com.openbackgroundgeolocation.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a refused upload does to the records it carried. Dropping them is
 * silent data loss; keeping ones the server will never take blocks every
 * record behind them.
 */
class HttpUploaderTest {

    @Test
    fun `a payload the server will not take is dropped`() {
        listOf(400, 403, 404, 413, 422).forEach { status ->
            assertFalse("HTTP $status", HttpUploader.keepsRecords(status))
        }
    }

    @Test
    fun `an expired token keeps the records for the next token`() {
        assertTrue(HttpUploader.keepsRecords(401))
    }

    @Test
    fun `a server asking for the request later keeps the records`() {
        assertTrue(HttpUploader.keepsRecords(408))
        assertTrue(HttpUploader.keepsRecords(429))
    }

    @Test
    fun `a server error keeps the records`() {
        listOf(500, 502, 503).forEach { status ->
            assertTrue("HTTP $status", HttpUploader.keepsRecords(status))
        }
    }
}
