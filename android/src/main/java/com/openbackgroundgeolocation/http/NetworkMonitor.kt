package com.openbackgroundgeolocation.http

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks connectivity and upload health so an outage is observable rather
 * than silent.
 *
 * Locations are always written to SQLite first, so nothing is lost when the
 * network drops — but without this the app, and the server, have no idea an
 * outage happened, how long it lasted, or how far behind the queue is. Every
 * number here is reported to JS via `getSyncStatus()` and attached to uploads
 * so a dashboard can show sync health per device.
 *
 * Pure state — no Android dependencies — so it is directly unit-testable.
 */
class NetworkMonitor {

    @Volatile
    private var connected = true

    /**
     * When the current outage began. Meaningful only while [connected] is
     * false — that flag, not a magic value here, is the source of truth, since
     * `0` is a legitimate timestamp.
     */
    @Volatile
    private var offlineSinceMillis = 0L

    /** Duration of the most recently completed outage. */
    @Volatile
    private var lastOfflineDurationMillis = 0L

    /** Cumulative offline time since the process started. */
    private val totalOfflineMillis = AtomicLong(0)

    private val outageCount = AtomicLong(0)

    /** Round-trip time of the last successful upload. */
    @Volatile
    private var lastLatencyMillis = 0L

    /** Rolling mean latency, so a single slow request does not dominate. */
    @Volatile
    private var averageLatencyMillis = 0L
    private val latencySampleCount = AtomicLong(0)

    @Volatile
    private var lastSuccessfulSyncAtMillis = 0L

    @Volatile
    private var lastFailureReason: String? = null

    @Volatile
    private var consecutiveFailures = 0

    // ── Connectivity transitions ──────────────────────────────────────────────

    /**
     * @return the completed outage duration in ms when this call ends an
     *   outage, otherwise `null`. Callers use it to report "we were offline
     *   for N seconds".
     */
    @Synchronized
    fun onConnected(nowMillis: Long = System.currentTimeMillis()): Long? {
        if (connected) return null
        connected = true
        // Reaching here means we were disconnected, so the start time is valid.
        // Clamped at zero because the wall clock can jump backwards (NTP sync,
        // or the user changing the time), which would otherwise yield a
        // negative outage.
        val duration = (nowMillis - offlineSinceMillis).coerceAtLeast(0L)
        lastOfflineDurationMillis = duration
        totalOfflineMillis.addAndGet(duration)
        offlineSinceMillis = 0L
        return duration
    }

    @Synchronized
    fun onDisconnected(nowMillis: Long = System.currentTimeMillis()) {
        if (!connected) return
        connected = false
        offlineSinceMillis = nowMillis
        outageCount.incrementAndGet()
    }

    val isConnected: Boolean get() = connected

    /** How long the current outage has lasted; `0` while connected. */
    fun currentOfflineDurationMillis(nowMillis: Long = System.currentTimeMillis()): Long =
        if (connected) 0L else (nowMillis - offlineSinceMillis).coerceAtLeast(0L)

    // ── Upload outcomes ───────────────────────────────────────────────────────

    fun onUploadSucceeded(latencyMillis: Long, nowMillis: Long = System.currentTimeMillis()) {
        lastLatencyMillis = latencyMillis
        val samples = latencySampleCount.incrementAndGet()
        // Incremental mean: avoids retaining a window of samples.
        averageLatencyMillis =
            averageLatencyMillis + (latencyMillis - averageLatencyMillis) / samples
        lastSuccessfulSyncAtMillis = nowMillis
        lastFailureReason = null
        consecutiveFailures = 0
    }

    fun onUploadFailed(reason: String) {
        lastFailureReason = reason
        consecutiveFailures += 1
    }

    /**
     * A slow network is not the same as no network. Uploads that succeed but
     * take longer than this suggest a degraded connection worth surfacing.
     */
    fun isNetworkSlow(): Boolean = lastLatencyMillis > SLOW_NETWORK_THRESHOLD_MILLIS

    // ── Reporting ─────────────────────────────────────────────────────────────

    /** Snapshot for `getSyncStatus()` and for attaching to uploads. */
    fun toJson(queuedRecordCount: Long, nowMillis: Long = System.currentTimeMillis()): JSONObject =
        JSONObject()
            .put("connected", connected)
            .put("offlineDurationMs", currentOfflineDurationMillis(nowMillis))
            .put("lastOfflineDurationMs", lastOfflineDurationMillis)
            .put("totalOfflineMs", totalOfflineMillis.get())
            .put("outageCount", outageCount.get())
            .put("queuedRecords", queuedRecordCount)
            .put("lastLatencyMs", lastLatencyMillis)
            .put("averageLatencyMs", averageLatencyMillis)
            .put("networkSlow", isNetworkSlow())
            .put("lastSuccessfulSyncAt", lastSuccessfulSyncAtMillis)
            .put("consecutiveFailures", consecutiveFailures)
            .apply { lastFailureReason?.let { put("lastFailureReason", it) } }

    companion object {
        /** Above this, a successful upload still indicates a degraded link. */
        const val SLOW_NETWORK_THRESHOLD_MILLIS = 3_000L
    }
}
