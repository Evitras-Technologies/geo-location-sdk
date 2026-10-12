package com.openbackgroundgeolocation.util

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bidirectional conversion between `org.json` documents (used internally and
 * for SQLite persistence) and the React Native bridge collection types.
 */
object JsonConverter {

    fun jsonToWritableMap(json: JSONObject): WritableMap {
        val map = Arguments.createMap()
        json.keys().forEach { key ->
            when (val value = json.get(key)) {
                is JSONObject -> map.putMap(key, jsonToWritableMap(value))
                is JSONArray -> map.putArray(key, jsonToWritableArray(value))
                is Boolean -> map.putBoolean(key, value)
                is Int -> map.putInt(key, value)
                is Long -> map.putDouble(key, value.toDouble())
                is Number -> map.putDouble(key, value.toDouble())
                is String -> map.putString(key, value)
                JSONObject.NULL -> map.putNull(key)
                else -> map.putString(key, value.toString())
            }
        }
        return map
    }

    fun jsonToWritableArray(json: JSONArray): WritableArray {
        val array = Arguments.createArray()
        for (index in 0 until json.length()) {
            when (val value = json.get(index)) {
                is JSONObject -> array.pushMap(jsonToWritableMap(value))
                is JSONArray -> array.pushArray(jsonToWritableArray(value))
                is Boolean -> array.pushBoolean(value)
                is Int -> array.pushInt(value)
                is Long -> array.pushDouble(value.toDouble())
                is Number -> array.pushDouble(value.toDouble())
                is String -> array.pushString(value)
                JSONObject.NULL -> array.pushNull()
                else -> array.pushString(value.toString())
            }
        }
        return array
    }

    fun readableMapToJson(map: ReadableMap): JSONObject {
        val json = JSONObject()
        val iterator = map.keySetIterator()
        while (iterator.hasNextKey()) {
            val key = iterator.nextKey()
            when (map.getType(key)) {
                ReadableType.Null -> json.put(key, JSONObject.NULL)
                ReadableType.Boolean -> json.put(key, map.getBoolean(key))
                ReadableType.Number -> json.put(key, map.getDouble(key))
                ReadableType.String -> json.put(key, map.getString(key))
                ReadableType.Map -> json.put(key, readableMapToJson(map.getMap(key)!!))
                ReadableType.Array -> json.put(key, readableArrayToJson(map.getArray(key)!!))
            }
        }
        return json
    }

    fun readableArrayToJson(array: ReadableArray): JSONArray {
        val json = JSONArray()
        for (index in 0 until array.size()) {
            when (array.getType(index)) {
                ReadableType.Null -> json.put(JSONObject.NULL)
                ReadableType.Boolean -> json.put(array.getBoolean(index))
                ReadableType.Number -> json.put(array.getDouble(index))
                ReadableType.String -> json.put(array.getString(index))
                // Nullable from RN 0.80 on; the type check above guarantees a value.
                ReadableType.Map -> json.put(readableMapToJson(array.getMap(index)!!))
                ReadableType.Array -> json.put(readableArrayToJson(array.getArray(index)!!))
            }
        }
        return json
    }
}
