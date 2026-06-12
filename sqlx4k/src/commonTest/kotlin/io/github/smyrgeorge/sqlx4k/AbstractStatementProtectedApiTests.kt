package io.github.smyrgeorge.sqlx4k

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import io.github.smyrgeorge.sqlx4k.impl.statement.AbstractStatement
import io.github.smyrgeorge.sqlx4k.impl.types.TypedNull
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests for the protected extension-point API of [AbstractStatement]:
 * [AbstractStatement.encode], [AbstractStatement.resolveNative], and their composition
 * with the protected SQL scanner helpers.
 *
 * External modules implement custom placeholder syntaxes on top of this API
 * (e.g. `ExtendedStatement` in sqlx4k-sqldelight, which renders PostgreSQL-style `$N`
 * parameters), so renaming, removing, or changing the behavior of these members breaks
 * downstream builds even though nothing else in this repository calls them.
 */
class AbstractStatementProtectedApiTests {

    private enum class Status { ACTIVE }

    private data class Money(val amount: Int, val currency: String)

    private object MoneyEncoder : ValueEncoder<Money> {
        override fun encode(value: Money): Any = "${value.amount}:${value.currency}"
        override fun decode(value: ResultSet.Row.Column): Money = error("not used in this test")
    }

    private class UnknownType

    /**
     * Minimal subclass consuming the protected API the same way an external
     * custom-placeholder statement implementation does.
     */
    private class TestStatement(sql: String = "select 1") : AbstractStatement(sql) {
        fun doEncode(value: Any?, encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY): String =
            encode(value, encoders)

        fun doResolveNative(value: Any?, encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY): Any? =
            resolveNative(value, encoders)

        /**
         * Renders custom `#N` placeholders (1-based) by composing the protected scanner
         * with [encode] — the same pattern ExtendedStatement uses for `$N` parameters.
         */
        fun renderHashPlaceholders(
            values: List<Any?>,
            encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY,
        ): String =
            sql.renderWithScanner { i, c, sb ->
                if (c != '#') return@renderWithScanner null
                var j = i + 1
                while (j < length && this[j].isDigit()) j++
                if (j == i + 1) return@renderWithScanner null
                val idx = substring(i + 1, j).toInt() - 1
                sb.append(encode(values[idx], encoders))
                j
            }
    }

    // ---- encode ----

    @Test
    fun `encode renders null and TypedNull as SQL null`() {
        val s = TestStatement()
        assertThat(s.doEncode(null)).isEqualTo("null")
        assertThat(s.doEncode(TypedNull(String::class))).isEqualTo("null")
    }

    @Test
    fun `encode quotes and escapes strings`() {
        val s = TestStatement()
        assertThat(s.doEncode("O'Brien")).isEqualTo("'O''Brien'")
        assertThat(s.doEncode("plain")).isEqualTo("'plain'")
    }

    @Test
    fun `encode renders primitives without quotes`() {
        val s = TestStatement()
        assertThat(s.doEncode(42)).isEqualTo("42")
        assertThat(s.doEncode(true)).isEqualTo("true")
        assertThat(s.doEncode(2.5)).isEqualTo("2.5")
    }

    @Test
    fun `encode renders enums as quoted names`() {
        val s = TestStatement()
        assertThat(s.doEncode(Status.ACTIVE)).isEqualTo("'ACTIVE'")
    }

    @Test
    fun `encode uses the provided encoder registry for custom types`() {
        val s = TestStatement()
        val encoders = ValueEncoderRegistry().register(MoneyEncoder)
        assertThat(s.doEncode(Money(15, "USD"), encoders)).isEqualTo("'15:USD'")
    }

    @Test
    fun `encode fails with MissingValueConverter for unsupported types`() {
        val s = TestStatement()
        val ex = assertFailsWith<SQLError> { s.doEncode(UnknownType()) }
        assertThat(ex.code).isEqualTo(SQLError.Code.MissingValueConverter)
    }

    // ---- resolveNative ----

    @Test
    fun `resolveNative returns null for null`() {
        val s = TestStatement()
        assertThat(s.doResolveNative(null)).isNull()
    }

    @Test
    fun `resolveNative passes TypedNull through unchanged`() {
        val s = TestStatement()
        val typedNull = TypedNull(Int::class)
        assertThat(s.doResolveNative(typedNull)).isSameInstanceAs(typedNull)
    }

    @Test
    fun `resolveNative passes builtin types through unchanged`() {
        val s = TestStatement()
        assertThat(s.doResolveNative(42)).isEqualTo(42)
        assertThat(s.doResolveNative("text")).isEqualTo("text")
        assertThat(s.doResolveNative(true)).isEqualTo(true)
    }

    @Test
    fun `resolveNative resolves enums to their name`() {
        val s = TestStatement()
        assertThat(s.doResolveNative(Status.ACTIVE)).isEqualTo("ACTIVE")
    }

    @Test
    fun `resolveNative uses the provided encoder registry for custom types`() {
        val s = TestStatement()
        val encoders = ValueEncoderRegistry().register(MoneyEncoder)
        assertThat(s.doResolveNative(Money(15, "USD"), encoders)).isEqualTo("15:USD")
    }

    @Test
    fun `resolveNative passes unknown types through unchanged`() {
        val s = TestStatement()
        val value = UnknownType()
        assertThat(s.doResolveNative(value)).isSameInstanceAs(value)
    }

    // ---- composition with the protected scanner ----

    @Test
    fun `custom placeholder syntax can be built from the scanner and encode helpers`() {
        val sql = "select #1 as a, '#2 inside a string' as s, #2 as b"
        val rendered = TestStatement(sql).renderHashPlaceholders(listOf("x'y", 7))
        assertThat(rendered).isEqualTo("select 'x''y' as a, '#2 inside a string' as s, 7 as b")
    }
}
