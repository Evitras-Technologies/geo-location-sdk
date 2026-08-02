package com.openbackgroundgeolocation.http

import org.json.JSONObject

/**
 * Builds the JSON body of an upload.
 *
 * Kept pure — no Android or network dependencies — because the exact shape of
 * this document is the SDK's contract with the customer's server, so it needs
 * to be directly unit-testable.
 */
object RequestBodyBuilder {

    /**
     * Wrap record(s) as `{ <rootProperty>: payload, ...deviceIdentity, ...params }`.
     *
     * A `rootProperty` of `"."` places a single record's fields at the body
     * root instead. Device identity is always included so a shared server can
     * attribute records without the host app doing anything; caller-supplied
     * `params` are merged last and therefore win on key collisions.
     */
    fun build(
        payload: Any,
        rootProperty: String,
        device: JSONObject,
        params: JSONObject
    ): JSONObject {
        val body = JSONObject()

        if (rootProperty == "." && payload is JSONObject) {
            payload.keys().forEach { key -> body.put(key, payload.get(key)) }
        } else {
            body.put(rootProperty, payload)
        }

        body.put("device_id", device.optString("id"))
        body.put("device_name", device.optString("name"))
        body.put("device", device)

        params.keys().forEach { key -> body.put(key, params.get(key)) }
        return body
    }

    /**
     * Body for a token-refresh request. Any value equal to `{refreshToken}` is
     * substituted with the live refresh token, matching the familiar plugin's
     * convention; with no template configured, a conventional
     * `{ "refresh_token": … }` is sent.
     */
    fun buildRefreshPayload(configuredPayload: JSONObject?, refreshToken: String): JSONObject {
        if (configuredPayload == null) {
            return JSONObject().put("refresh_token", refreshToken)
        }
        val payload = JSONObject()
        configuredPayload.keys().forEach { key ->
            val value = configuredPayload.optString(key)
            payload.put(key, if (value == REFRESH_TOKEN_PLACEHOLDER) refreshToken else value)
        }
        return payload
    }

    const val REFRESH_TOKEN_PLACEHOLDER = "{refreshToken}"
}
