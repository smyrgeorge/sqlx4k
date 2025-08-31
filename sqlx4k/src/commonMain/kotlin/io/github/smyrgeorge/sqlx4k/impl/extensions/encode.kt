@file:OptIn(ExperimentalTime::class)

package io.github.smyrgeorge.sqlx4k.impl.extensions

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

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
