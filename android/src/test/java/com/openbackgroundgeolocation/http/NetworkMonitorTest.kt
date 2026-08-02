package com.openbackgroundgeolocation.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Connectivity accounting. These numbers drive what an operator sees when a
 * device goes dark, so an outage must be measured accurately and a recovery
 * must be unambiguous.
 */
class NetworkMonitorTest {

    @Test
    fun `starts optimistic so the first upload is attempted`() {
        // Assuming offline at boot would stall the queue until a connectivity
        // callback happened to fire.
        assertTrue(NetworkMonitor().isConnected)
    }

    @Test
    fun `records the duration of a completed outage`() {
        val monitor = NetworkMonitor()
        monitor.onDisconnected(nowMillis = 1_000)
        val duration = monitor.onConnected(nowMillis = 31_000)
        assertEquals(30_000L, duration)
        assertTrue(monitor.isConnected)
    }

    @Test
    fun `reconnecting without an outage reports nothing`() {
        // Duplicate onAvailable callbacks are common; they must not invent
        // zero-length outages or fire spurious "back online" reporting.
        assertNull(NetworkMonitor().onConnected(nowMillis = 5_000))
    }

    @Test
    fun `repeated disconnect callbacks do not restart the clock`() {
        val monitor = NetworkMonitor()
        monitor.onDisconnected(nowMillis = 1_000)
        monitor.onDisconnected(nowMillis = 9_000) // ignored
        assertEquals(20_000L, monitor.onConnected(nowMillis = 21_000))
    }

    @Test
    fun `current offline duration grows while disconnected`() {
        val monitor = NetworkMonitor()
        monitor.onDisconnected(nowMillis = 1_000)
        assertEquals(4_000L, monitor.currentOfflineDurationMillis(nowMillis = 5_000))
        assertFalse(monitor.isConnected)
    }

    @Test
    fun `current offline duration is zero while connected`() {
        assertEquals(0L, NetworkMonitor().currentOfflineDurationMillis(nowMillis = 99_000))
    }

    @Test
    fun `outages accumulate across multiple drops`() {
        val monitor = NetworkMonitor()
        monitor.onDisconnected(nowMillis = 0)
        monitor.onConnected(nowMillis = 10_000) // 10s
        monitor.onDisconnected(nowMillis = 20_000)
        monitor.onConnected(nowMillis = 25_000) // 5s

        val status = monitor.toJson(queuedRecordCount = 0, nowMillis = 30_000)
        assertEquals(2, status.getInt("outageCount"))
        assertEquals(15_000L, status.getLong("totalOfflineMs"))
        assertEquals(5_000L, status.getLong("lastOfflineDurationMs"))
    }

    @Test
    fun `latency averages incrementally`() {
        val monitor = NetworkMonitor()
        monitor.onUploadSucceeded(latencyMillis = 100)
        monitor.onUploadSucceeded(latencyMillis = 300)
        val status = monitor.toJson(queuedRecordCount = 0)
        assertEquals(300L, status.getLong("lastLatencyMs"))
        assertEquals(200L, status.getLong("averageLatencyMs"))
    }

    @Test
    fun `a slow but working link is distinguished from no link`() {
        val monitor = NetworkMonitor()
        monitor.onUploadSucceeded(latencyMillis = 5_000)
        assertTrue(monitor.isNetworkSlow())
        assertTrue(monitor.isConnected) // slow is not offline
    }

    @Test
    fun `fast uploads are not flagged as slow`() {
        val monitor = NetworkMonitor()
        monitor.onUploadSucceeded(latencyMillis = 250)
        assertFalse(monitor.isNetworkSlow())
    }

    @Test
    fun `failures accumulate and clear on success`() {
        val monitor = NetworkMonitor()
        monitor.onUploadFailed("timeout")
        monitor.onUploadFailed("timeout")
        var status = monitor.toJson(queuedRecordCount = 3)
        assertEquals(2, status.getInt("consecutiveFailures"))
        assertEquals("timeout", status.getString("lastFailureReason"))

        monitor.onUploadSucceeded(latencyMillis = 120)
        status = monitor.toJson(queuedRecordCount = 0)
        assertEquals(0, status.getInt("consecutiveFailures"))
        assertFalse(status.has("lastFailureReason"))
    }

    @Test
    fun `an outage starting at epoch zero is still measured`() {
        // Regression: a `offlineSinceMillis > 0` sentinel silently scored this
        // outage as zero, because 0 is a legitimate timestamp.
        val monitor = NetworkMonitor()
        monitor.onDisconnected(nowMillis = 0)
        assertEquals(10_000L, monitor.onConnected(nowMillis = 10_000))
    }

    @Test
    fun `a backwards clock jump cannot produce a negative outage`() {
        // NTP correction or a manual time change can move the wall clock back.
        val monitor = NetworkMonitor()
        monitor.onDisconnected(nowMillis = 60_000)
        assertEquals(0L, monitor.onConnected(nowMillis = 10_000))
    }

    @Test
    fun `status reports the queue depth it is given`() {
        val status = NetworkMonitor().toJson(queuedRecordCount = 42)
        assertEquals(42L, status.getLong("queuedRecords"))
    }
}
