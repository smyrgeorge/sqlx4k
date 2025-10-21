@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package io.github.smyrgeorge.sqlx4k.impl.extensions

import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement.ValueEncoderRegistry

import io.github.smyrgeorge.sqlx4k.impl.types.DoubleQuotingString
import io.github.smyrgeorge.sqlx4k.impl.types.NoQuotingString
import io.github.smyrgeorge.sqlx4k.impl.types.NoWrappingTuple
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Converts the value of the receiver to a string representation suitable for database operations.
 *
 * This method handles various types:
 * - `null` is represented as the string "null".
 * - `String` values are wrapped in single quotes and any single quotes within the string are escaped.
 * - Numeric and boolean values are converted to their string representation using `toString()`.
 * - For other types, it attempts to use a custom renderer. If no renderer is found, it throws a [SQLError].
 *
 * @param encoders A map of encoders for specific types, used to encode values into a SQL-compatible format.
 * @return A string representation of the receiver suitable for database operations.
 * @throws SQLError if the type of the receiver is unsupported and no appropriate renderer is found.
 */
fun Any?.encodeValue(encoders: ValueEncoderRegistry): String {
    return when (this) {
        null -> "null"
        is String -> {
            // Fast path: if no single quote present, avoid replace allocation
            if (indexOf('\'') < 0) return "'${this}'"
            // https://stackoverflow.com/questions/12316953/insert-text-with-single-quotes-in-postgresql
            // https://stackoverflow.com/questions/9596652/how-to-escape-apostrophe-a-single-quote-in-mysql
            // https://stackoverflow.com/questions/603572/escape-single-quote-character-for-use-in-an-sqlite-query
            "'${replace("'", "''")}'"
        }

        is DoubleQuotingString -> "\"${NoQuotingString(value).encodeValue(encoders)}\""

        is NoQuotingString -> {
            // Fast path: if no single quote present, avoid replace allocation
            if (value.indexOf('\'') < 0) return "$this"
            // https://stackoverflow.com/questions/12316953/insert-text-with-single-quotes-in-postgresql
            // https://stackoverflow.com/questions/9596652/how-to-escape-apostrophe-a-single-quote-in-mysql
            // https://stackoverflow.com/questions/603572/escape-single-quote-character-for-use-in-an-sqlite-query
            value.replace("'", "''")
        }

        is Char -> "'${if (this == '\'') "''" else this}'"
        is Boolean, is Number -> toString()
        is Instant -> "'${toTimestampString()}'"
        is LocalDate, is LocalTime, is LocalDateTime -> "'${this}'"
        is Uuid -> "'${this}'"
        is Iterable<*> -> encodeTuple(encoders)
        is BooleanArray -> asIterable().encodeTuple(encoders)
        is ShortArray -> asIterable().encodeTuple(encoders)
        is IntArray -> asIterable().encodeTuple(encoders)
        is LongArray -> asIterable().encodeTuple(encoders)
        is FloatArray -> asIterable().encodeTuple(encoders)
        is DoubleArray -> asIterable().encodeTuple(encoders)
        is Array<*> -> asIterable().encodeTuple(encoders)
        is NoWrappingTuple -> value.encodeTuple(encoders, false)

        else -> {
            val error = SQLError(
                code = SQLError.Code.NamedParameterTypeNotSupported,
                message = "Could not map named parameter of type ${this::class.simpleName}"
            )

            val encoder = encoders.get(this::class) ?: error.ex()
            encoder.encode(this).encodeValue(encoders)
        }
    }
}

/**
 * Helper to encode a collection/array of values as a SQL tuple like (a, b, c).
 * Uses encodeValue for each element; nulls become null without quotes.
 */
private fun Iterable<*>.encodeTuple(encoders: ValueEncoderRegistry, wrapInParenthesis: Boolean = true): String =
    if (wrapInParenthesis) joinToString(", ", "(", ")") { it.encodeValue(encoders) }
    else joinToString(", ") { it.encodeValue(encoders) }


/**
 * Converts the `Instant` to a string representation of a timestamp with microsecond precision.
 *
 * The resulting string is formatted as `YYYY-MM-DD HH:MM:SS.mmmuuu` (e.g., `2023-01-01 12:34:56.123456`),
 * where `mmmuuu` represents the fractional seconds with six digits for microseconds. The conversion is
 * performed based on the provided `timeZone`.
 *
 * @param timeZone the `TimeZone` used to convert the `Instant` to a local date-time representation.
 *                 Defaults to `TimeZone.UTC` if not specified.
 * @return a `String` representing the timestamp in the specified format.
 */
fun Instant.toTimestampString(timeZone: TimeZone = TimeZone.UTC): String {
    val instant = this
    val ldt = instant.toLocalDateTime(timeZone)

    // Fractional part: microseconds (6 digits)
    val micros = instant.nanosecondsOfSecond / 1_000
    val microsStr = micros.toString().padStart(6, '0')

    return buildString(26) {
        append(ldt.year.toString().padStart(4, '0')); append('-')
        append(ldt.month.number.toString().padStart(2, '0')); append('-')
        append(ldt.day.toString().padStart(2, '0')); append(' ')
        append(ldt.hour.toString().padStart(2, '0')); append(':')
        append(ldt.minute.toString().padStart(2, '0')); append(':')
        append(ldt.second.toString().padStart(2, '0')); append('.')
        append(microsStr)
    }
}
