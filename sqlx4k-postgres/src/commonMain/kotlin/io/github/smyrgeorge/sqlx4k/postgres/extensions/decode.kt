@file:Suppress("unused")

package io.github.smyrgeorge.sqlx4k.postgres.extensions

import io.github.smyrgeorge.sqlx4k.ResultSet

fun ResultSet.Row.Column.asBoolean(): Boolean = asString().asBoolean()
fun ResultSet.Row.Column.asBooleanOrNull(): Boolean? = asStringOrNull()?.asBoolean()
fun ResultSet.Row.Column.asBooleanArray(): BooleanArray = asString().asStringArray().map { it.asBoolean() }.toBooleanArray()
fun ResultSet.Row.Column.asBooleanArrayOrNull(): BooleanArray? = asStringOrNull()?.asStringArray()?.map { it.asBoolean() }?.toBooleanArray()
fun ResultSet.Row.Column.asShortArray(): ShortArray = asString().asStringArray().map { it.toShort() }.toShortArray()
fun ResultSet.Row.Column.asShortArrayOrNull(): ShortArray? = asStringOrNull()?.asStringArray()?.map { it.toShort() }?.toShortArray()
fun ResultSet.Row.Column.asIntArray(): IntArray = asString().asStringArray().map { it.toInt() }.toIntArray()
fun ResultSet.Row.Column.asIntArrayOrNull(): IntArray? = asStringOrNull()?.asStringArray()?.map { it.toInt() }?.toIntArray()
fun ResultSet.Row.Column.asLongArray(): LongArray = asString().asStringArray().map { it.toLong() }.toLongArray()
fun ResultSet.Row.Column.asLongArrayOrNull(): LongArray? = asStringOrNull()?.asStringArray()?.map { it.toLong() }?.toLongArray()
fun ResultSet.Row.Column.asFloatArray(): FloatArray = asString().asStringArray().map { it.toFloat() }.toFloatArray()
fun ResultSet.Row.Column.asFloatArrayOrNull(): FloatArray? = asStringOrNull()?.asStringArray()?.map { it.toFloat() }?.toFloatArray()
fun ResultSet.Row.Column.asDoubleArray(): DoubleArray = asString().asStringArray().map { it.toDouble() }.toDoubleArray()
fun ResultSet.Row.Column.asDoubleArrayOrNull(): DoubleArray? = asStringOrNull()?.asStringArray()?.map { it.toDouble() }?.toDoubleArray()
fun ResultSet.Row.Column.asByteArray(): ByteArray = asString().removePrefix("\\x").hexToByteArray()
fun ResultSet.Row.Column.asByteArrayOrNull(): ByteArray? = asStringOrNull()?.removePrefix("\\x")?.hexToByteArray()

private fun String.asBoolean(): Boolean {
    require(this == "t" || this == "f") { "Invalid boolean value: $this" }
    return this == "t"
}

private fun String.asStringArray(): List<String> {
    val s = trim()
    require(s.startsWith("{") && s.endsWith("}")) { "Invalid Postgres array format: '$this'" }
    val body = s.substring(1, s.length - 1)
    if (body.isEmpty()) return emptyList()
    return body.split(',')
}
