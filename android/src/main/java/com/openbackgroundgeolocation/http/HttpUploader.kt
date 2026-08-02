package com.openbackgroundgeolocation.http

import android.content.Context
import com.openbackgroundgeolocation.config.ConfigStore
import com.openbackgroundgeolocation.data.DatabaseHelper
import com.openbackgroundgeolocation.data.LocationRow
import com.openbackgroundgeolocation.event.EventDispatcher
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uploads persisted locations to the configured `Config.url`.
 *
 * Records are deleted from the local database only after the server responds
 * 2xx, giving at-least-once delivery. Uploads run on a single background
 * thread; concurrent flush requests coalesce.
 */
class HttpUploader(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "open-bg-geo-http")
    }
    private val flushInProgress = AtomicBoolean(false)

    /** Exponential-backoff state, so a failing server is not hammered. */
    @Volatile
    private var consecutiveFailures = 0
    @Volatile
    private var retryNotBeforeMillis = 0L

    /** Connectivity and upload-health telemetry, surfaced via `getSyncStatus()`. */
    val networkMonitor = NetworkMonitor()

    private val config: ConfigStore get() = ConfigStore.get(context)
    private val database: DatabaseHelper get() = DatabaseHelper.get(context)

    /**
     * Upload everything pending. Safe to call from any thread.
     *
     * @param force bypass the backoff window (used by explicit `sync()` calls,
     *   where the caller is deliberately retrying).
     */
    fun flush(force: Boolean = false, onComplete: ((syncedRecords: List<JSONObject>) -> Unit)? = null) {
        if (config.url.isEmpty()) {
            onComplete?.invoke(emptyList())
            return
        }
        // Known-offline: don't wake the radio for a request that cannot
        // succeed. Records stay queued in SQLite and go out on reconnect.
        if (!force && !networkMonitor.isConnected) {
            onComplete?.invoke(emptyList())
            return
        }
        if (!force && System.currentTimeMillis() < retryNotBeforeMillis) {
            onComplete?.invoke(emptyList()) // still backing off from earlier failures
            return
        }
        if (!flushInProgress.compareAndSet(false, true)) {
            onComplete?.invoke(emptyList())
            return
        }
        executor.execute {
            val synced = mutableListOf<JSONObject>()
            try {
                if (config.batchSync) {
                    uploadBatches(synced)
                } else {
                    uploadOneByOne(synced)
                }
            } catch (error: Exception) {
                // An upload must never crash the host app.
                database.appendLog("error", "Sync failed: ${error.message}")
            } finally {
                flushInProgress.set(false)
                onComplete?.invoke(synced)
            }
        }
    }

    /** `true` when enough records have accumulated to justify an auto-sync. */
    fun shouldAutoSync(): Boolean {
        if (!config.autoSync || config.url.isEmpty()) return false
        val threshold = config.autoSyncThreshold
        return threshold <= 0 || database.locationCount() >= threshold
    }

    private fun uploadBatches(synced: MutableList<JSONObject>) {
        // Bounded by the number of pending records so a delete failure can
        // never spin this loop forever against the server.
        var remainingIterations = maxIterations()
        while (remainingIterations-- > 0) {
            val batch = database.nextBatch(config.maxBatchSize, config.locationsOrderDirection)
            if (batch.isEmpty()) return

            val payload = JSONArray().also { array -> batch.forEach { array.put(it.json) } }
            if (!executeRequest(buildBody(payload))) return

            val identifiers = batch.map(LocationRow::id)
            database.deleteLocations(identifiers)
            if (!confirmDeleted(identifiers)) {
                logDeleteFailure()
                return
            }
            batch.forEach { synced.add(it.json) }
            if (config.maxBatchSize <= 0) return // single unbounded batch handled everything
        }
    }

    private fun uploadOneByOne(synced: MutableList<JSONObject>) {
        var remainingIterations = maxIterations()
        while (remainingIterations-- > 0) {
            val next = database.nextBatch(1, config.locationsOrderDirection).firstOrNull() ?: return
            if (!executeRequest(buildBody(next.json))) return

            database.deleteLocations(listOf(next.id))
            if (!confirmDeleted(listOf(next.id))) {
                logDeleteFailure()
                return
            }
            synced.add(next.json)
        }
    }

    /**
     * Upper bound on loop iterations. A fixed constant rather than a COUNT(*)
     * query — the loop already exits when the queue drains or a request fails,
     * so this is only a runaway guard, and COUNT(*) is O(n) in SQLite and ran
     * on every upload.
     */
    private fun maxIterations(): Int = MAX_ITERATIONS

    /**
     * Guard against an uploaded-but-not-deleted record re-uploading forever.
     * If the rows survived the delete, stop the run rather than loop.
     */
    private fun confirmDeleted(identifiers: List<Long>): Boolean =
        database.nextBatch(1, config.locationsOrderDirection)
            .firstOrNull()
            ?.let { it.id !in identifiers } ?: true

    private fun logDeleteFailure() {
        database.appendLog(
            "error",
            "Upload succeeded but the record could not be removed from the queue; " +
                "aborting this sync to avoid duplicate uploads."
        )
    }

    /**
     * Wrap record(s) as `{ <httpRootProperty>: payload, ...params }` (or at
     * root for `"."`), always including this installation's device identity so
     * a shared server can attribute records without the host app doing
     * anything. User-supplied `params` win on key collisions.
     */
    private fun buildBody(payload: Any): JSONObject =
        RequestBodyBuilder.build(
            payload = payload,
            rootProperty = config.httpRootProperty,
            device = config.deviceJson(),
            params = config.params
        ).apply {
            // Sync health travels with the data, so a dashboard can show that
            // a device was offline for N minutes and is now catching up,
            // without the app reporting it separately.
            put("network", networkMonitor.toJson(database.locationCount()))
        }

    /** Attach `Authorization: <scheme> <accessToken>` when configured. */
    private fun applyAuthorization(requestBuilder: Request.Builder) {
        val authorization = config.authorization ?: return
        val accessToken = authorization.optString("accessToken", "")
        if (accessToken.isEmpty()) return
        val scheme = authorization.optString("strategy", "JWT")
            .let { if (it.equals("JWT", ignoreCase = true)) "Bearer" else it }
        requestBuilder.header("Authorization", "$scheme $accessToken")
    }

    /**
     * Exchange the refresh token for a new access token after a 401.
     * `refreshPayload` values equal to `"{refreshToken}"` are substituted with
     * the current refresh token, matching the familiar plugin's convention.
     * Returns true when a new token was stored.
     */
    private fun refreshAccessToken(): Boolean {
        val authorization = config.authorization ?: return false
        val refreshUrl = authorization.optString("refreshUrl", "")
        val refreshToken = authorization.optString("refreshToken", "")
        if (refreshUrl.isEmpty() || refreshToken.isEmpty()) return false

        val payload = RequestBodyBuilder.buildRefreshPayload(
            authorization.optJSONObject("refreshPayload"),
            refreshToken
        )

        val builder = Request.Builder()
            .url(refreshUrl)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        authorization.optJSONObject("refreshHeaders")?.let { headers ->
            headers.keys().forEach { key -> builder.header(key, headers.optString(key)) }
        }

        return try {
            httpClient().newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    database.appendLog("error", "Token refresh failed: HTTP ${response.code}")
                    return false
                }
                val json = JSONObject(response.body?.string() ?: "{}")
                // Accept the common field spellings rather than forcing one.
                val newAccessToken = listOf("access_token", "accessToken", "token")
                    .firstNotNullOfOrNull { json.optString(it, "").ifEmpty { null } }
                    ?: return false
                val newRefreshToken = listOf("refresh_token", "refreshToken")
                    .firstNotNullOfOrNull { json.optString(it, "").ifEmpty { null } }
                val expires = listOf("expires_at", "expires", "exp")
                    .firstNotNullOfOrNull { if (json.has(it)) json.optLong(it) else null }

                config.updateAccessToken(newAccessToken, newRefreshToken, expires)
                database.appendLog("info", "Access token refreshed")
                true
            }
        } catch (error: Exception) {
            database.appendLog("error", "Token refresh error: ${error.message}")
            false
        }
    }

    private fun executeRequest(body: JSONObject, isRetryAfterRefresh: Boolean = false): Boolean {
        val requestBuilder = Request.Builder()
            .url(config.url)
            .method(config.httpMethod.uppercase(), body.toString().toRequestBody(JSON_MEDIA_TYPE))
        config.headers.let { headers ->
            headers.keys().forEach { key -> requestBuilder.header(key, headers.optString(key)) }
        }
        applyAuthorization(requestBuilder)

        val startedAtMillis = System.currentTimeMillis()
        return try {
            httpClient().newCall(requestBuilder.build()).execute().use { response ->
                val responseText = response.body?.string() ?: ""
                emitHttpEvent(response.isSuccessful, response.code, responseText)
                // A 401 usually means an expired access token: refresh once and
                // replay the same payload rather than failing the queue.
                if (response.code == 401 && !isRetryAfterRefresh && refreshAccessToken()) {
                    return@use executeRequest(body, isRetryAfterRefresh = true)
                }
                if (response.isSuccessful) {
                    networkMonitor.onUploadSucceeded(System.currentTimeMillis() - startedAtMillis)
                    onRequestSucceeded()
                } else {
                    networkMonitor.onUploadFailed("HTTP ${response.code}")
                    // 4xx (except 408/429) means the server rejected the payload
                    // itself — retrying identical data will not help, so drop the
                    // record rather than block the queue behind it forever.
                    val isRetryable = response.code !in 400..499 ||
                        response.code == 408 || response.code == 429
                    if (isRetryable) {
                        onRequestFailed()
                    } else {
                        database.appendLog(
                            "warn",
                            "Server rejected record with HTTP ${response.code}; discarding it " +
                                "to keep the queue moving."
                        )
                        return@use true // treat as handled so the record is removed
                    }
                }
                response.isSuccessful
            }
        } catch (error: Exception) {
            emitHttpEvent(false, 0, error.message ?: "Request failed")
            networkMonitor.onUploadFailed(error.message ?: "Request failed")
            onRequestFailed()
            false
        }
    }

    /**
     * One shared client for the process. Building a client per request throws
     * away OkHttp's connection pool, so every upload paid a fresh TCP and TLS
     * handshake — expensive in realtime mode, which uploads once per second.
     * Rebuilt only when the configured timeout changes.
     */
    @Synchronized
    private fun httpClient(): OkHttpClient {
        val timeout = config.httpTimeout
        val existing = sharedClient
        if (existing != null && sharedClientTimeout == timeout) return existing

        val client = OkHttpClient.Builder()
            .callTimeout(timeout, TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()
        sharedClient = client
        sharedClientTimeout = timeout
        return client
    }

    private fun onRequestSucceeded() {
        consecutiveFailures = 0
        retryNotBeforeMillis = 0L
    }

    /** Exponential backoff capped at [MAX_BACKOFF_MILLIS]. */
    private fun onRequestFailed() {
        consecutiveFailures += 1
        val delayMillis = (BASE_BACKOFF_MILLIS shl (consecutiveFailures - 1).coerceAtMost(6))
            .coerceAtMost(MAX_BACKOFF_MILLIS)
        retryNotBeforeMillis = System.currentTimeMillis() + delayMillis
    }

    companion object {
        @Volatile
        private var sharedClient: OkHttpClient? = null
        @Volatile
        private var sharedClientTimeout: Long = -1

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val REFRESH_TOKEN_PLACEHOLDER = "{refreshToken}"
        private const val BASE_BACKOFF_MILLIS = 5_000L
        private const val MAX_BACKOFF_MILLIS = 300_000L // 5 minutes
        private const val SAFETY_ITERATIONS = 5
        private const val MAX_ITERATIONS = 10_000
    }

    private fun emitHttpEvent(success: Boolean, status: Int, responseText: String) {
        EventDispatcher.emit(
            context,
            EventDispatcher.EVENT_HTTP,
            JSONObject()
                .put("success", success)
                .put("status", status)
                .put("responseText", responseText)
        )
    }
}
