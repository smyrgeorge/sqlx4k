package io.github.smyrgeorge.sqlx4k.sqlite

import kotlin.math.absoluteValue

/**
 * Renders a REAL in the layout SQLite uses for `cast(x as text)` (`1.5`, `3.0`, `1.0e+300`,
 * `1.23e-05`, `Inf`), but with the shortest digits that round-trip to [value].
 *
 * SQLite's own text depends on its version (before 3.47 it keeps only 15 significant digits, so e.g.
 * `avg(..)` loses precision), and the JVM, Android and native drivers each run a different one.
 * Formatting the double here keeps them in agreement; the native driver does the same in Rust.
 */
internal fun formatSqliteReal(value: Double): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Inf" else "-Inf"
    if (value == 0.0) return "0.0"

    val (digits, exponent) = shortestDigits(value.absoluteValue)
    val sign = if (value < 0) "-" else ""
    return when {
        exponent < -4 || exponent >= 17 -> {
            val fraction = digits.substring(1).ifEmpty { "0" }
            val exp = exponent.absoluteValue.toString().padStart(2, '0')
            "$sign${digits[0]}.${fraction}e${if (exponent < 0) '-' else '+'}$exp"
        }

        exponent >= 0 -> {
            val intLen = exponent + 1
            if (digits.length > intLen) "$sign${digits.substring(0, intLen)}.${digits.substring(intLen)}"
            else "$sign${digits.padEnd(intLen, '0')}.0"
        }

        else -> "${sign}0.${"0".repeat(-exponent - 1)}$digits"
    }
}

/**
 * The shortest significant digits (no leading or trailing zeros) and the decimal exponent of the
 * first digit that round-trip to [value], which must be positive and finite.
 */
private fun shortestDigits(value: Double): Pair<String, Int> {
    // Double.toString() round-trips on every platform ("1.5", "1.0E300", "1.23E-6"), but it is not
    // the shortest representation on every one, so drop digits while the value still round-trips.
    val s = value.toString()
    val e = s.indexOfFirst { it == 'E' || it == 'e' }
    val mantissa = if (e < 0) s else s.substring(0, e)
    val point = mantissa.indexOf('.').let { if (it < 0) mantissa.length else it }
    var digits = mantissa.replace(".", "")
    var exponent = point - 1 + (if (e < 0) 0 else s.substring(e + 1).toInt())
    val leadingZeros = digits.indexOfFirst { it != '0' }
    digits = digits.substring(leadingZeros).trimEnd('0')
    exponent -= leadingZeros

    while (digits.length > 1) {
        var shorter = digits.substring(0, digits.length - 1)
        var shorterExponent = exponent
        if (digits.last() >= '5') {
            val incremented = shorter.trimEnd('9')
            if (incremented.isEmpty()) {
                shorter = "1"
                shorterExponent += 1
            } else {
                shorter = incremented.dropLast(1) + (incremented.last() + 1)
            }
        }
        shorter = shorter.trimEnd('0')
        if ("0.${shorter}E${shorterExponent + 1}".toDouble() != value) break
        digits = shorter
        exponent = shorterExponent
    }
    return digits to exponent
}
