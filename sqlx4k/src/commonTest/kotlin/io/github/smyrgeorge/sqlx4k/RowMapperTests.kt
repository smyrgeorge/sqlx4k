package io.github.smyrgeorge.sqlx4k

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RowMapperTests {

    private val empty = ValueEncoderRegistry.EMPTY

    /** Builds a non-error ResultSet with one single-column row per supplied value. */
    private fun resultSet(vararg values: String): ResultSet {
        val rows = values.map { v ->
            ResultSet.Row(listOf(ResultSet.Row.Column(ordinal = 0, name = "c0", type = "text", value = v)))
        }
        return ResultSet(rows, null, ResultSet.Metadata(emptyList()))
    }

    private val stringMapper = object : RowMapper<String> {
        override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): String =
            row.get(0).asString()
    }

    private val intMapper = object : RowMapper<Int> {
        override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): Int =
            row.get(0).asString().toInt()
    }

    @Test
    fun `default map on an empty result set returns an empty list`() {
        val result = stringMapper.map(resultSet(), empty)
        assertThat(result).isEmpty()
    }

    @Test
    fun `default map preserves row order mapping column 0 to String`() {
        val result = stringMapper.map(resultSet("a", "b", "c"), empty)
        assertThat(result).containsExactly("a", "b", "c")
    }

    @Test
    fun `default map preserves row order mapping column 0 to Int`() {
        val result = intMapper.map(resultSet("1", "2", "3"), empty)
        assertThat(result).containsExactly(1, 2, 3)
    }

    @Test
    fun `default map propagates an exception thrown by the per-row map`() {
        val throwing = object : RowMapper<String> {
            override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): String =
                throw IllegalStateException("boom")
        }
        val ex = assertFailsWith<IllegalStateException> {
            throwing.map(resultSet("x", "y"), empty)
        }
        assertThat(ex.message).isEqualTo("boom")
    }
}
