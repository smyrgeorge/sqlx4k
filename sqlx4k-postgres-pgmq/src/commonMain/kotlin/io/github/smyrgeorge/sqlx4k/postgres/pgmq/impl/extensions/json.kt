package io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.extensions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun primitive(value: Any): JsonPrimitive = when (value) {
    is Number -> JsonPrimitive(value)
    is UByte -> JsonPrimitive(value)
    is UShort -> JsonPrimitive(value)
    is UInt -> JsonPrimitive(value)
    is ULong -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}

private fun List<*>.toJsonElement(): JsonElement {
    val list: MutableList<JsonElement> = mutableListOf()
    forEach {
        val value = it ?: return@forEach
        when (value) {
            is Map<*, *> -> list.add((value).toJsonElement())
            is List<*> -> list.add(value.toJsonElement())
            else -> list.add(primitive(value))
        }
    }
    return JsonArray(list)
}

private fun Map<*, *>.toJsonElement(): JsonElement {
    val map: MutableMap<String, JsonElement> = mutableMapOf()
    forEach {
        val key = it.key as? String ?: return@forEach
        val value = it.value ?: return@forEach
        when (value) {
            is Map<*, *> -> map[key] = (value).toJsonElement()
            is List<*> -> map[key] = value.toJsonElement()
            else -> map[key] = primitive(value)
        }
    }
    return JsonObject(map)
}

internal fun Map<*, *>.toJsonString(): String = toJsonElement().toString()

/**
 * Decodes message headers into a string map, leniently: headers written by SQL or by another pgmq client may hold any
 * JSON. A string value is kept as is, any other value as its JSON text (`1`, `true`, `["a"]`), and a null value is
 * left out; headers that are not a JSON object decode to an empty map. A strict decoding failed the whole read for
 * every message read with such a message, again on every retry.
 */
internal fun String.toStringMap(): Map<String, String> {
    val headers = Json.parseToJsonElement(this) as? JsonObject ?: return emptyMap()
    return buildMap {
        headers.forEach { (key, value) ->
            when {
                value is JsonNull -> {}
                value is JsonPrimitive && value.isString -> put(key, value.content)
                else -> put(key, value.toString())
            }
        }
    }
}
