package io.github.smyrgeorge.sqlx4k.impl.mappers

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import kotlin.test.Test
import kotlin.test.assertFailsWith

class StringRowMapperTests {

    private val empty = ValueEncoderRegistry.EMPTY

    /** Builds a single-column-per-value row; pass `null` to model a NULL column. */
    private fun row(vararg values: String?): ResultSet.Row =
        ResultSet.Row(values.mapIndexed { i, v ->
            ResultSet.Row.Column(ordinal = i, name = "c$i", type = "text", value = v)
        })

    // ========================================================================================
    // map(row)
    // ========================================================================================

    @Test
    fun `map with a single column returns the string value`() {
        val result = StringRowMapper.map(row("hello"), empty)
        assertThat(result).isEqualTo("hello")
    }

    @Test
    fun `map with zero columns throws IllegalArgumentException with exact message`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            StringRowMapper.map(row(), empty)
        }
        assertThat(ex.message).isEqualTo("Expected a single column, got 0")
    }

    @Test
    fun `map with two columns throws IllegalArgumentException with exact message`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            StringRowMapper.map(row("a", "b"), empty)
        }
        assertThat(ex.message).isEqualTo("Expected a single column, got 2")
    }

    @Test
    fun `map with three columns reports the actual column count`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            StringRowMapper.map(row("a", "b", "c"), empty)
        }
        assertThat(ex.message).isEqualTo("Expected a single column, got 3")
    }

    @Test
    fun `map with a single NULL column propagates the SQLError from asString`() {
        val ex = assertFailsWith<SQLError> {
            StringRowMapper.map(row(null), empty)
        }
        assertThat(ex.code).isEqualTo(SQLError.Code.CannotDecode)
        assertThat(ex.message).isEqualTo("[CannotDecode] :: Failed to decode value (null)")
    }

    // ========================================================================================
    // Result<List<String>>.toSingleString()  (member-extension of the StringRowMapper object)
    // ========================================================================================

    @Test
    fun `toSingleString success with exactly one element returns success of that element`() {
        with(StringRowMapper) {
            val result = Result.success(listOf("only")).toSingleString()
            assertThat(result.isSuccess).isEqualTo(true)
            assertThat(result.getOrNull()).isEqualTo("only")
        }
    }

    @Test
    fun `toSingleString success with zero elements returns failure wrapping IllegalStateException`() {
        with(StringRowMapper) {
            val result = Result.success(emptyList<String>()).toSingleString()
            assertThat(result.isFailure).isEqualTo(true)
            val ex = result.exceptionOrNull()
            assertThat(ex).isNotNull()
            assertThat(ex is IllegalStateException).isEqualTo(true)
            assertThat(ex?.message).isEqualTo("Expected a single row, got 0")
        }
    }

    @Test
    fun `toSingleString success with two elements returns failure wrapping IllegalStateException`() {
        with(StringRowMapper) {
            val result = Result.success(listOf("a", "b")).toSingleString()
            assertThat(result.isFailure).isEqualTo(true)
            val ex = result.exceptionOrNull()
            assertThat(ex).isNotNull()
            assertThat(ex is IllegalStateException).isEqualTo(true)
            assertThat(ex?.message).isEqualTo("Expected a single row, got 2")
        }
    }

    @Test
    fun `toSingleString passes an already-failed result through unchanged`() {
        with(StringRowMapper) {
            val original = RuntimeException("boom")
            val result: Result<String> = Result.failure<List<String>>(original).toSingleString()
            assertThat(result.isFailure).isEqualTo(true)
            // Same exception instance is propagated (no re-wrapping).
            assertThat(result.exceptionOrNull()).isSameInstanceAs(original)
        }
    }
}
