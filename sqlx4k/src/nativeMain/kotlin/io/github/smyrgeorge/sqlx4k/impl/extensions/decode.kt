@file:Suppress("unused")

package io.github.smyrgeorge.sqlx4k.impl.extensions

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlinx.datetime.*
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

fun ResultSet.Row.Column.asChar(): Char {
    require(asString().length == 1) { "The column ($name) value is not a char (length != 1)." }
    return asString()[0]
}

fun ResultSet.Row.Column.asCharOrNull(): Char? = asStringOrNull()?.get(0)
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

@OptIn(ExperimentalUuidApi::class)
fun ResultSet.Row.Column.asUuid(): Uuid = Uuid.parse(asString())

@OptIn(ExperimentalUuidApi::class)
fun ResultSet.Row.Column.asUuidOrNull(): Uuid? = asStringOrNull()?.let { Uuid.parse(it) }

inline fun <reified T : Enum<T>> ResultSet.Row.Column.asEnum(): T = asString().toEnum<T>()
inline fun <reified T : Enum<T>> ResultSet.Row.Column.asEnumOrNull(): T? = asStringOrNull()?.toEnum<T>()
fun ResultSet.Row.Column.asLocalDate(): LocalDate = LocalDate.parse(asString())
fun ResultSet.Row.Column.asLocalDateOrNull(): LocalDate? = asStringOrNull()?.let { LocalDate.parse(it) }
fun ResultSet.Row.Column.asLocalTime(): LocalTime = LocalTime.parse(asString())
fun ResultSet.Row.Column.asLocalTimeOrNull(): LocalTime? = asStringOrNull()?.let { LocalTime.parse(it) }
fun ResultSet.Row.Column.asLocalDateTime(): LocalDateTime = LocalDateTime.parse(asString(), localDateTimeFormatter)
fun ResultSet.Row.Column.asLocalDateTimeOrNull(): LocalDateTime? =
    asStringOrNull()?.let { LocalDateTime.parse(it, localDateTimeFormatter) }

fun ResultSet.Row.Column.asInstant(): Instant = asString().toInstantSqlx4k()
fun ResultSet.Row.Column.asInstantOrNull(): Instant? = asStringOrNull()?.toInstantSqlx4k()

inline fun <reified T : Enum<T>> String.toEnum(): T =
    try {
        enumValueOf<T>(this)
    } catch (e: Exception) {
        SQLError(SQLError.Code.CannotDecodeEnumValue, "Cannot decode enum value '$this'.").ex()
    }

private fun String.toInstantSqlx4k(): Instant {
    val split = split("+")
    @OptIn(ExperimentalTime::class)
    return LocalDateTime.parse(split[0], localDateTimeFormatter)
        .toInstant(UtcOffset(hours = split[1].toInt()))
        .toDeprecatedInstant()
}

private val localDateTimeFormatter: DateTimeFormat<LocalDateTime> = LocalDateTime.Format {
    @OptIn(FormatStringsInDatetimeFormats::class)
    byUnicodePattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]")
}
