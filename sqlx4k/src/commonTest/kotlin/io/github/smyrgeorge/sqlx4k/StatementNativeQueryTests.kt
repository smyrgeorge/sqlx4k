package io.github.smyrgeorge.sqlx4k

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.impl.types.NoWrappingTuple
import io.github.smyrgeorge.sqlx4k.impl.types.TypedNull
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Unit tests for [Statement.renderNativeQuery] and the parameter-binding machinery in
 * `AbstractStatement`.
 *
 * These replace the old `render()` (value-inlining) tests. Under the native-query model,
 * parameters are rendered as positional placeholders (`$1`, `$2`, ... for PostgreSQL, `?` for
 * MySQL/SQLite) and the raw, unescaped values are collected into a separate ordered list.
 */
class StatementNativeQueryTests {

    private val empty = ValueEncoderRegistry.EMPTY

    private enum class Color { RED, GREEN }

    private data class Money(val amount: Double, val currency: String)
    private class MoneyEncoder : ValueEncoder<Money> {
        override fun encode(value: Money): Any = "${value.amount}:${value.currency}"
        override fun decode(value: ResultSet.Row.Column): Money = error("not needed")
    }

    // ========================================================================================
    // Parameter extraction
    // ========================================================================================

    @Test
    fun `extracts positional parameter count`() {
        val st = Statement.create("select * from t where a = ? and b = ?")
        assertThat(st.extractedPositionalParameters).isEqualTo(2)
        assertThat(st.extractedNamedParameters).isEmpty()
    }

    @Test
    fun `extracts named parameter set`() {
        val st = Statement.create("select * from t where a = :a and b = :b and c = :a")
        assertThat(st.extractedNamedParameters).isEqualTo(setOf("a", "b"))
        assertThat(st.extractedPositionalParameters).isEqualTo(0)
    }

    // ========================================================================================
    // Positional parameters (PostgreSQL)
    // ========================================================================================

    @Test
    fun `positional parameters render as dollar placeholders in order`() {
        val nq = Statement.create("select * from t where a = ? and b = ?")
            .bind(0, 10)
            .bind(1, "x")
            .renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.sql).isEqualTo($$"select * from t where a = $1 and b = $2")
        assertThat(nq.values).containsExactly(10, "x")
    }

    @Test
    fun `positional parameters keep placeholder order regardless of bind order`() {
        val nq = Statement.create("select * from t where a = ? and b = ?")
            .bind(1, "x")
            .bind(0, 10)
            .renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.sql).isEqualTo($$"select * from t where a = $1 and b = $2")
        assertThat(nq.values).containsExactly(10, "x")
    }

    @Test
    fun `rebinding a positional index overrides the previous value`() {
        val nq = Statement.create("select ?")
            .bind(0, 1)
            .bind(0, 2)
            .renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.values).containsExactly(2)
    }

    // ========================================================================================
    // Named parameters (PostgreSQL)
    // ========================================================================================

    @Test
    fun `named parameters render as dollar placeholders`() {
        val nq = Statement.create("select * from t where a = :a and b = :b")
            .bind("a", 10)
            .bind("b", "x")
            .renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.sql).isEqualTo($$"select * from t where a = $1 and b = $2")
        assertThat(nq.values).containsExactly(10, "x")
    }

    @Test
    fun `a repeated named parameter emits one placeholder and one value per occurrence`() {
        val nq = Statement.create("select :x, :x")
            .bind("x", 7)
            .renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.sql).isEqualTo($$"select $1, $2")
        assertThat(nq.values).containsExactly(7, 7)
    }

    // ========================================================================================
    // Mixed positional + named
    // ========================================================================================

    @Test
    fun `mixed positional and named parameters render in textual order`() {
        val nq = Statement.create("select * from t where a = ? and b = :b")
            .bind(0, 10)
            .bind("b", 20)
            .renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.sql).isEqualTo($$"select * from t where a = $1 and b = $2")
        assertThat(nq.values).containsExactly(10, 20)
    }

    // ========================================================================================
    // Dialect differences
    // ========================================================================================

    @Test
    fun `MySQL renders question-mark placeholders`() {
        val nq = Statement.create("select * from t where a = ? and b = :b")
            .bind(0, 1)
            .bind("b", 2)
            .renderNativeQuery(Dialect.MySQL, empty)
        assertThat(nq.sql).isEqualTo("select * from t where a = ? and b = ?")
        assertThat(nq.values).containsExactly(1, 2)
    }

    @Test
    fun `SQLite renders question-mark placeholders`() {
        val nq = Statement.create("select :a, :b")
            .bind("a", 1)
            .bind("b", 2)
            .renderNativeQuery(Dialect.SQLite, empty)
        assertThat(nq.sql).isEqualTo("select ?, ?")
        assertThat(nq.values).containsExactly(1, 2)
    }

    // ========================================================================================
    // Value types are passed through raw (no quoting / no escaping)
    // ========================================================================================

    @Test
    fun `primitive and string values pass through unquoted and unescaped`() {
        val nq = Statement.create("values (?, ?, ?, ?, ?)")
            .bind(0, "O'Brien")   // NOT escaped in native mode
            .bind(1, 42)
            .bind(2, 3.14)
            .bind(3, true)
            .bind(4, 9_000_000_000L)
            .renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.values).containsExactly("O'Brien", 42, 3.14, true, 9_000_000_000L)
    }

    @Test
    fun `null value is carried as a null entry`() {
        val nq = Statement.create("values (?, ?)")
            .bind(0, "a")
            .bind(1, null)
            .renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.sql).isEqualTo($$"values ($1, $2)")
        assertThat(nq.values).containsExactly("a", null)
    }

    @Test
    fun `bindNull carries a TypedNull entry preserving the intended type`() {
        val nq = Statement.create("values (?)")
            .bindNull(0, String::class)
            .renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.values).containsExactly(TypedNull(String::class))
    }

    @Test
    fun `enum value is resolved to its name`() {
        val nq = Statement.create("values (?)")
            .bind(0, Color.GREEN)
            .renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.values).containsExactly("GREEN")
    }

    @Test
    fun `custom type is resolved through the encoder registry`() {
        val registry = ValueEncoderRegistry().register(MoneyEncoder())
        val nq = Statement.create("values (?)")
            .bind(0, Money(100.5, "USD"))
            .renderNativeQuery(Dialect.PostgreSQL, registry)
        assertThat(nq.values).containsExactly("100.5:USD")
    }

    // ========================================================================================
    // Collections expand into multiple placeholders
    // ========================================================================================

    @Test
    fun `a list expands into a parenthesized placeholder group`() {
        val nq = Statement.create("select * from t where id in ?")
            .bind(0, listOf(1, 2, 3))
            .renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.sql).isEqualTo($$"select * from t where id in ($1, $2, $3)")
        assertThat(nq.values).containsExactly(1, 2, 3)
    }

    @Test
    fun `a named list parameter expands into a parenthesized placeholder group`() {
        val nq = Statement.create("select * from t where id in :ids")
            .bind("ids", setOf(5, 6))
            .renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.sql).isEqualTo($$"select * from t where id in ($1, $2)")
        assertThat(nq.values).containsExactly(5, 6)
    }

    @Test
    fun `a primitive array expands into placeholders`() {
        val nq = Statement.create("select * from t where id in ?")
            .bind(0, intArrayOf(7, 8))
            .renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.sql).isEqualTo($$"select * from t where id in ($1, $2)")
        assertThat(nq.values).containsExactly(7, 8)
    }

    @Test
    fun `NoWrappingTuple expands without surrounding parentheses`() {
        val nq = Statement.create("select array[?]")
            .bind(0, NoWrappingTuple(listOf(1, 2, 3)))
            .renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.sql).isEqualTo($$"select array[$1, $2, $3]")
        assertThat(nq.values).containsExactly(1, 2, 3)
    }

    @Test
    fun `expanding an empty collection raises EmptyCollection`() {
        val ex = assertFailsWith<SQLError> {
            Statement.create("select * from t where id in ?")
                .bind(0, emptyList<Int>())
                .renderNativeQuery(Dialect.PostgreSQL, empty)
        }
        assertThat(ex.code).isEqualTo(SQLError.Code.EmptyCollection)
    }

    // ========================================================================================
    // Placeholders inside strings / comments / casts are NOT treated as parameters
    // ========================================================================================

    @Test
    fun `question mark inside a single-quoted literal is not a parameter`() {
        val st = Statement.create("select '?' as q")
        assertThat(st.extractedPositionalParameters).isEqualTo(0)
        val nq = st.renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.sql).isEqualTo("select '?' as q")
        assertThat(nq.values).isEmpty()
    }

    @Test
    fun `placeholders inside a line comment are ignored`() {
        val st = Statement.create("select 1 -- :x and ?\nwhere a = ?")
        assertThat(st.extractedPositionalParameters).isEqualTo(1)
        assertThat(st.extractedNamedParameters).isEmpty()
        val nq = st.bind(0, 5).renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.sql).isEqualTo($$"select 1 -- :x and ?\nwhere a = $1")
        assertThat(nq.values).containsExactly(5)
    }

    @Test
    fun `placeholders inside a block comment are ignored`() {
        val st = Statement.create("select /* :x ? */ :y")
        assertThat(st.extractedNamedParameters).isEqualTo(setOf("y"))
        val nq = st.bind("y", 1).renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.sql).isEqualTo($$"select /* :x ? */ $1")
        assertThat(nq.values).containsExactly(1)
    }

    @Test
    fun `placeholders inside a dollar-quoted string are ignored`() {
        val st = Statement.create("select $$ :x and ? $$, :y")
        assertThat(st.extractedNamedParameters).isEqualTo(setOf("y"))
        assertThat(st.extractedPositionalParameters).isEqualTo(0)
    }

    @Test
    fun `postgres double-colon cast is not treated as a named parameter`() {
        val nq = Statement.create("select ?::jsonb, :x::text")
            .bind(0, "{}")
            .bind("x", "hi")
            .renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.sql).isEqualTo($$"select $1::jsonb, $2::text")
        assertThat(nq.values).containsExactly("{}", "hi")
    }

    // ========================================================================================
    // No parameters -> passthrough
    // ========================================================================================

    @Test
    fun `a statement without parameters renders unchanged with no values`() {
        val nq = Statement.create("select 1").renderNativeQuery(Dialect.PostgreSQL, empty)
        assertThat(nq.sql).isEqualTo("select 1")
        assertThat(nq.values).isEmpty()
    }

    // ========================================================================================
    // Binding / rendering error paths
    // ========================================================================================

    @Test
    fun `binding a positional index out of bounds raises`() {
        val ex = assertFailsWith<SQLError> {
            Statement.create("select ?").bind(3, 1)
        }
        assertThat(ex.code).isEqualTo(SQLError.Code.PositionalParameterOutOfBounds)
    }

    @Test
    fun `binding an unknown named parameter raises`() {
        val ex = assertFailsWith<SQLError> {
            Statement.create("select :a").bind("b", 1)
        }
        assertThat(ex.code).isEqualTo(SQLError.Code.NamedParameterNotFound)
    }

    @Test
    fun `rendering with an unbound positional parameter raises`() {
        val ex = assertFailsWith<SQLError> {
            Statement.create("select ?, ?").bind(0, 1).renderNativeQuery(Dialect.PostgreSQL, empty)
        }
        assertThat(ex.code).isEqualTo(SQLError.Code.PositionalParameterValueNotSupplied)
    }

    @Test
    fun `rendering with an unbound named parameter raises`() {
        val ex = assertFailsWith<SQLError> {
            Statement.create("select :a, :b").bind("a", 1).renderNativeQuery(Dialect.PostgreSQL, empty)
        }
        assertThat(ex.code).isEqualTo(SQLError.Code.NamedParameterValueNotSupplied)
    }
}
