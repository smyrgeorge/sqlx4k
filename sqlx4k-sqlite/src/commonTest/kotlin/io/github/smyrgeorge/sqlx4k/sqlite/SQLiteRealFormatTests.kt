package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

/** Mirrors `real_tests` in the Rust driver, so every platform renders REAL values identically. */
class SQLiteRealFormatTests {

    @Test
    fun `formats reals like sqlite with shortest digits`() {
        val cases = listOf(
            1.5 to "1.5",
            3.0 to "3.0",
            -2.5 to "-2.5",
            0.0 to "0.0",
            -0.0 to "0.0",
            10000.0 to "10000.0",
            -0.0042 to "-0.0042",
            0.0001234 to "0.0001234",
            0.00001234 to "1.234e-05",
            1.23e-6 to "1.23e-06",
            123456789.0001 to "123456789.0001",
            7.0 / 3.0 to "2.3333333333333335",
            1.0 / 3.0 to "0.3333333333333333",
            0.1 + 0.2 to "0.30000000000000004",
            0.1 to "0.1",
            4.35 to "4.35",
            1e16 to "10000000000000000.0",
            9.99e16 to "99900000000000000.0",
            1e17 to "1.0e+17",
            123456789012345678.0 to "1.2345678901234568e+17",
            1e300 to "1.0e+300",
            -1e-300 to "-1.0e-300",
            Double.MIN_VALUE to "5.0e-324",
            Double.MAX_VALUE to "1.7976931348623157e+308",
            Double.POSITIVE_INFINITY to "Inf",
            Double.NEGATIVE_INFINITY to "-Inf",
        )
        assertAll {
            for ((value, expected) in cases) {
                assertThat(formatSqliteReal(value), "formatting $value").isEqualTo(expected)
            }
        }
    }
}
