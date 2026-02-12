@file:OptIn(ExperimentalUuidApi::class)

package io.github.smyrgeorge.sqlx4k.impl.extensions

import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.types.NoWrappingTuple
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

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
internal fun Any?.encodeValue(encoders: ValueEncoderRegistry): String {
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

        is Char -> "'${if (this == '\'') "''" else this}'"
        is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> toString()
        is Instant -> "'${toTimestampString()}'"
        is LocalDate, is LocalTime, is LocalDateTime -> "'${this}'"
        is Uuid -> "'${this}'"
        is Enum<*> -> "'${name}'"
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
            val encoder = encoders.get(this::class)
                ?: SQLError(
                    code = SQLError.Code.MissingValueConverter,
                    message = "Could not encode value of type ${this::class.simpleName}"
                ).raise()
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
internal fun Instant.toTimestampString(timeZone: TimeZone = TimeZone.UTC): String {
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

/**
 * Resolves a native value from the given object, using the specified encoders to handle
 * custom-encoded types. The method identifies and processes known types directly or delegates
 * encoding to a corresponding `ValueEncoder`, if available.
 *
 * @param encoders The registry of `ValueEncoder` instances used to encode custom types.
 * @return The resolved native value, suitable for use in database operations. If the input is
 * null, null is returned. If the input is a recognized type, it is returned as-is. For
 * unrecognized types, the value is passed through a compatible `ValueEncoder`, if available.
 */
internal fun Any?.resolveNativeValue(encoders: ValueEncoderRegistry): Any? {
    return when (this) {
        null -> null
        is String, is Char, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> this
        is Instant, is LocalDate, is LocalTime, is LocalDateTime -> this
        is Uuid -> this
        is Enum<*> -> name
        else -> {
            val encoder = encoders.get(this::class)
            if (encoder != null) {
                encoder.encode(this).resolveNativeValue(encoders)
            } else {
                // Pass through: the driver handles known types like
                // Instant, LocalDate, Uuid, etc.
                this
            }
        }
    }
}

/**
 * Appends a native value to a `StringBuilder` and maintains a list of resolved values
 * and a counter for placeholders, based on the specified SQL dialect and value encoders.
 *
 * This method is responsible for resolving the input value and appending appropriate
 * SQL syntax to the `StringBuilder`. It supports iterable structures, arrays, primitive array types,
 * and single values. For iterable structures and arrays, it optionally wraps the output in parentheses.
 * It uses a placeholder format specific to the provided SQL dialect.
 *
 * @param value The value to be resolved and appended. This can be a single value, an iterable, or an array.
 * @param sb The `StringBuilder` to which the SQL syntax and placeholders are appended.
 * @param values A mutable list where resolved native values are stored.
 * @param counter An array representing the counter for placeholder numbers. The first element is incremented.
 * @param dialect The SQL `Dialect` to determine the placeholder formatting (e.g., `$` for PostgreSQL, `?` for MySQL).
 * @param encoders The `ValueEncoderRegistry` used to resolve custom-encoded values.
 */
internal fun appendNativeValue(
    value: Any?,
    sb: StringBuilder,
    values: MutableList<Any?>,
    counter: IntArray,
    dialect: Dialect,
    encoders: ValueEncoderRegistry
) {
    fun appendPlaceholder() {
        counter[0]++
        when (dialect) {
            Dialect.PostgreSQL -> sb.append('$').append(counter[0])
            Dialect.MySQL, Dialect.SQLite -> sb.append('?')
        }
    }

    fun appendExpanded(iterable: Iterable<*>, wrap: Boolean) {
        if (wrap) sb.append('(')
        var first = true
        for (element in iterable) {
            if (!first) sb.append(", ")
            first = false
            values.add(element.resolveNativeValue(encoders))
            appendPlaceholder()
        }
        if (wrap) sb.append(')')
    }

    when (value) {
        is NoWrappingTuple -> appendExpanded(value.value, wrap = false)
        is Iterable<*> -> appendExpanded(value, wrap = true)
        is BooleanArray -> appendExpanded(value.asIterable(), wrap = true)
        is ShortArray -> appendExpanded(value.asIterable(), wrap = true)
        is IntArray -> appendExpanded(value.asIterable(), wrap = true)
        is LongArray -> appendExpanded(value.asIterable(), wrap = true)
        is FloatArray -> appendExpanded(value.asIterable(), wrap = true)
        is DoubleArray -> appendExpanded(value.asIterable(), wrap = true)
        is Array<*> -> appendExpanded(value.asIterable(), wrap = true)
        else -> {
            values.add(value.resolveNativeValue(encoders))
            appendPlaceholder()
        }
    }
}
