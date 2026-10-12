package com.openbackgroundgeolocation.http

import org.json.JSONArray
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
     * Body of an upload whose records the host has given a shape
     * (`locationTemplate`): each record rendered through [template], placed as
     * [build] places it, with `params` merged last, and nothing else.
     *
     * No device identity here: a server that validates what it is sent turns
     * a request down for one field it does not know, and the host that wrote
     * the template has said exactly which fields that server takes.
     *
     * A record the template cannot shape is sent as it is, so that a broken
     * template shows up as refused uploads and not as silence.
     */
    fun buildFromTemplate(
        payload: Any,
        template: String,
        rootProperty: String,
        params: JSONObject
    ): JSONObject {
        val shaped: Any = when (payload) {
            is JSONObject -> render(template, payload) ?: payload
            is JSONArray -> JSONArray().also { records ->
                for (index in 0 until payload.length()) {
                    val record = payload.optJSONObject(index)
                    records.put(
                        if (record == null) payload.get(index) else render(template, record) ?: record
                    )
                }
            }
            else -> payload
        }

        val body = JSONObject()
        if (rootProperty == "." && shaped is JSONObject) {
            shaped.keys().forEach { key -> body.put(key, shaped.get(key)) }
        } else {
            body.put(rootProperty, shaped)
        }
        params.keys().forEach { key -> body.put(key, params.get(key)) }
        return body
    }

    /**
     * [template] with every `<%= name %>` tag replaced by that value of
     * [record], or null when what comes out is not a JSON object.
     *
     * A bare name is looked for among the coordinates first (`latitude`,
     * `speed`), then on the record itself (`timestamp`, `uuid`); a dotted
     * name is a path from the record (`battery.level`). A value the record
     * does not have is written as `null`.
     */
    fun render(template: String, record: JSONObject): JSONObject? {
        val text = TAG.replace(template) { tag -> literal(lookUp(record, tag.groupValues[1])) }
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun lookUp(record: JSONObject, name: String): Any? {
        if (!name.contains('.')) {
            return record.optJSONObject("coords")?.opt(name) ?: record.opt(name)
        }
        var current: Any? = record
        for (part in name.split('.')) {
            current = (current as? JSONObject)?.opt(part) ?: return null
        }
        return current
    }

    /** A value as it is written into the template: text escaped, not quoted. */
    private fun literal(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is Number -> JSONObject.numberToString(value)
        is Boolean, is JSONObject, is JSONArray -> value.toString()
        else -> JSONObject.quote(value.toString()).let { quoted ->
            quoted.substring(1, quoted.length - 1)
        }
    }

    private val TAG = Regex("<%=\\s*([\\w.]+)\\s*%>")

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
