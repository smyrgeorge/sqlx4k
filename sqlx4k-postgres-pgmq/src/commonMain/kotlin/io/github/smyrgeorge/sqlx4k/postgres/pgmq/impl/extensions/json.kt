package io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.extensions

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalSerializationApi::class)
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

private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
internal fun String.toStringMap(): Map<String, String> = Json.decodeFromString(mapSerializer, this)
