@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
@file:Suppress("unused")

package io.github.smyrgeorge.sqlx4k.impl.extensions

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlinx.datetime.*
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

fun ResultSet.Row.Column.asChar(): Char = asString().asChar()
fun ResultSet.Row.Column.asCharOrNull(): Char? = asStringOrNull()?.asChar()
fun ResultSet.Row.Column.asInt(): Int = asString().toInt()
fun ResultSet.Row.Column.asIntOrNull(): Int? = asStringOrNull()?.toInt()
fun ResultSet.Row.Column.asLong(): Long = asString().toLong()
fun ResultSet.Row.Column.asLongOrNull(): Long? = asStringOrNull()?.toLong()
fun ResultSet.Row.Column.asShort(): Short = asString().toShort()
fun ResultSet.Row.Column.asShortOrNull(): Short? = asStringOrNull()?.toShort()
fun ResultSet.Row.Column.asFloat(): Float = asString().toFloat()
fun ResultSet.Row.Column.asFloatOrNull(): Float? = asStringOrNull()?.toFloat()
fun ResultSet.Row.Column.asDouble(): Double = asString().toDouble()
fun ResultSet.Row.Column.asDoubleOrNull(): Double? = asStringOrNull()?.toDouble()
fun ResultSet.Row.Column.asBoolean(): Boolean = asString().asBoolean()
fun ResultSet.Row.Column.asBooleanOrNull(): Boolean? = asStringOrNull()?.asBoolean()
fun ResultSet.Row.Column.asUuid(): Uuid = Uuid.parse(asString())
fun ResultSet.Row.Column.asUuidOrNull(): Uuid? = asStringOrNull()?.let { Uuid.parse(it) }
inline fun <reified T : Enum<T>> ResultSet.Row.Column.asEnum(): T = asString().toEnum<T>()
inline fun <reified T : Enum<T>> ResultSet.Row.Column.asEnumOrNull(): T? = asStringOrNull()?.toEnum<T>()
fun ResultSet.Row.Column.asLocalDate(): LocalDate = LocalDate.parse(asString())
fun ResultSet.Row.Column.asLocalDateOrNull(): LocalDate? = asStringOrNull()?.let { LocalDate.parse(it) }
fun ResultSet.Row.Column.asLocalTime(): LocalTime = LocalTime.parse(asString())
fun ResultSet.Row.Column.asLocalTimeOrNull(): LocalTime? = asStringOrNull()?.let { LocalTime.parse(it) }
fun ResultSet.Row.Column.asLocalDateTime(): LocalDateTime = LocalDateTime.parse(asString(), localDateTimeFormatter)
fun ResultSet.Row.Column.asLocalDateTimeOrNull(): LocalDateTime? = asStringOrNull()?.let { LocalDateTime.parse(it, localDateTimeFormatter) }
fun ResultSet.Row.Column.asInstant(): Instant = asString().asInstant()
fun ResultSet.Row.Column.asInstantOrNull(): Instant? = asStringOrNull()?.asInstant()
fun ResultSet.Row.Column.asByteArray(): ByteArray = asString().removePrefix("\\x").hexToByteArray()
fun ResultSet.Row.Column.asByteArrayOrNull(): ByteArray? = asStringOrNull()?.removePrefix("\\x")?.hexToByteArray()

private fun String.asChar(): Char {
    require(length == 1) { "Invalid char value: $this" }
    return this[0]
}

private fun String.asBoolean(): Boolean {
    return when (this) {
        "1", "t", "true" -> true
        "0", "f", "false" -> false
        else -> error("Invalid boolean value: $this")
    }
}

inline fun <reified T : Enum<T>> String.toEnum(): T {
    return try {
        enumValueOf<T>(this)
    } catch (e: Exception) {
        SQLError(SQLError.Code.CannotDecodeEnumValue, "Cannot decode enum value '$this'.").ex()
    }
}

private val localDateTimeFormatter: DateTimeFormat<LocalDateTime> = LocalDateTime.Format {
    @OptIn(FormatStringsInDatetimeFormats::class)
    byUnicodePattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]")
}

// Match: "yyyy-MM-dd HH:mm:ss[.fraction][(Z|+HH|+HHMM|+HH:MM|-HH|-HHMM|-HH:MM)]" (offset optional)
private val timestampRegex = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d+)?)(Z|[+-]\d{2}(?::?\d{2})?)?$""")

@OptIn(ExperimentalTime::class)
private fun String.asInstant(): Instant {
    fun normalizeFractionTo6(s: String): String {
        val dot = s.indexOf('.')
        if (dot < 0) return s
        val frac = s.substring(dot + 1)
        val normalized = when {
            frac.length == 6 -> frac
            frac.length < 6 -> frac.padEnd(6, '0')
            else -> frac.take(6)
        }
        return s.take(dot + 1) + normalized
    }

    fun parseUtcOffset(s: String): UtcOffset {
        if (s == "Z" || s == "z") return UtcOffset.ZERO
        val sign = if (s[0] == '-') -1 else 1
        val body = s.substring(1)
        val (hh, mm) = when {
            body.contains(":") -> {
                val parts = body.split(":")
                if (parts.size != 2) error("Invalid offset: '$s'")
                parts[0] to parts[1]
            }

            body.length == 2 -> body to "00"
            body.length == 4 -> body.take(2) to body.substring(2)
            else -> error("Invalid offset: '$s'")
        }
        val hours = hh.toIntOrNull() ?: error("Invalid offset hours in '$s'")
        val minutes = mm.toIntOrNull() ?: error("Invalid offset minutes in '$s'")
        return UtcOffset(hours = sign * hours, minutes = sign * minutes)
    }

    val m = timestampRegex.matchEntire(trim())
        ?: error("Invalid timestamp with optional offset: '$this'")

    val dateTimePart = normalizeFractionTo6(m.groupValues[1])
    val offsetPart = m.groupValues.getOrNull(2)

    val ldt = LocalDateTime.parse(dateTimePart, localDateTimeFormatter)
    val offset = if (offsetPart.isNullOrEmpty()) UtcOffset.ZERO else parseUtcOffset(offsetPart)
    return ldt.toInstant(offset)
}
