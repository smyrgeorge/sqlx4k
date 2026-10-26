@file:Suppress("unused")

package io.github.smyrgeorge.sqlx4k.postgres.extensions

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLocalDateTime
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime

//@formatter:off
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
fun ResultSet.Row.Column.asStringList(): List<String> = asString().asStringArray()
fun ResultSet.Row.Column.asStringListOrNull(): List<String>? = asStringOrNull()?.asStringArray()
fun ResultSet.Row.Column.asNullableStringList(): List<String?> = asString().asNullableStringArray()
fun ResultSet.Row.Column.asNullableStringListOrNull(): List<String?>? = asStringOrNull()?.asNullableStringArray()
fun ResultSet.Row.Column.asUuidList(): List<Uuid> = asString().asStringArray().map(Uuid::parse)
fun ResultSet.Row.Column.asUuidListOrNull(): List<Uuid>? = asStringOrNull()?.asStringArray()?.map(Uuid::parse)
fun ResultSet.Row.Column.asLocalDateTimeList(): List<LocalDateTime> = asString().asStringArray().map { it.asLocalDateTimeElement() }
fun ResultSet.Row.Column.asLocalDateTimeListOrNull(): List<LocalDateTime>? = asStringOrNull()?.asStringArray()?.map { it.asLocalDateTimeElement() }
fun ResultSet.Row.Column.asInstantList(): List<Instant> = asString().asStringArray().map { it.asInstantElement() }
fun ResultSet.Row.Column.asInstantListOrNull(): List<Instant>? = asStringOrNull()?.asStringArray()?.map { it.asInstantElement() }
fun ResultSet.Row.Column.asByteArrayList(): List<ByteArray> = asString().asStringArray().map { it.removePrefix("\\x").hexToByteArray() }
fun ResultSet.Row.Column.asByteArrayListOrNull(): List<ByteArray>? = asStringOrNull()?.asStringArray()?.map { it.removePrefix("\\x").hexToByteArray() }
//@formatter:on

private fun String.asBoolean(): Boolean {
    require(this == "t" || this == "f") { "Invalid boolean value: $this" }
    return this == "t"
}

/**
 * Parses a postgres array literal in either the bare form (`{a,b,c}`, what our
 * native reader emits and what postgres uses for numeric / bool arrays) or the
 * canonical quoted form (`{"a","b"}`, what the R2DBC driver hands back for
 * text-shaped arrays). Inside double-quoted elements, `\"` and `\\` are
 * unescaped per the postgres array I/O rules.
 *
 * Limitation: assumes 1-D arrays. Multi-dimensional arrays (`{{1,2},{3,4}}`)
 * are not supported.
 */
private fun String.asStringArray(): List<String> = asNullableStringArray().map {
    it ?: SQLError(
        code = SQLError.Code.CannotDecode,
        message = "Postgres array '$this' holds a NULL element: read it with asNullableStringList()."
    ).raise()
}

/** Like [asStringArray], but a NULL element (a bare `NULL`, unlike the quoted string `"NULL"`) is read as null. */
private fun String.asNullableStringArray(): List<String?> {
    val s = trim()
    require(s.startsWith("{") && s.endsWith("}")) { "Invalid Postgres array format: '$this'" }
    val body = s.substring(1, s.length - 1)
    if (body.isEmpty()) return emptyList()

    val out = mutableListOf<String?>()
    val cur = StringBuilder()
    var inQuotes = false
    var quoted = false
    fun element(): String? = cur.toString().takeUnless { !quoted && it.equals("NULL", ignoreCase = true) }
    var i = 0
    while (i < body.length) {
        val c = body[i]
        when {
            inQuotes && c == '\\' && i + 1 < body.length -> {
                cur.append(body[i + 1])
                i += 2
            }

            c == '"' -> {
                inQuotes = !inQuotes
                quoted = true
                i++
            }

            !inQuotes && c == ',' -> {
                out.add(element())
                cur.clear()
                quoted = false
                i++
            }

            else -> {
                cur.append(c)
                i++
            }
        }
    }
    out.add(element())
    return out
}

private fun String.asLocalDateTimeElement(): LocalDateTime =
    ResultSet.Row.Column(ordinal = 0, name = "", type = "", value = this).asLocalDateTime()

private fun String.asInstantElement(): Instant =
    ResultSet.Row.Column(ordinal = 0, name = "", type = "", value = this).asInstant()
