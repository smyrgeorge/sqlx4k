package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.processor.util.Account
import io.github.smyrgeorge.sqlx4k.processor.test.generated.AccountAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.insert
import io.github.smyrgeorge.sqlx4k.processor.test.generated.update
import kotlin.test.Test

/**
 * Tests that properties annotated with @Transient are fully excluded from generated code:
 * not part of INSERT/UPDATE/RETURNING and not mapped from query results.
 */
class TransientColumnTests {

    private val emptyRegistry = ValueEncoderRegistry.EMPTY

    private fun column(ordinal: Int, name: String, type: String, value: String?): ResultSet.Row.Column =
        ResultSet.Row.Column(ordinal, name, type, value)

    private fun row(vararg columns: ResultSet.Row.Column): ResultSet.Row =
        ResultSet.Row(columns.toList())

    @Test
    fun `Account insert excludes transient columns`() {
        val account = Account(id = 0, email = "alice@example.com")
        val sql = account.insert().render()

        assertThat(sql).contains("insert into accounts")
        assertThat(sql).contains("email")
        assertThat(sql).doesNotContain("display_name")
        assertThat(sql).doesNotContain("domain")
    }

    @Test
    fun `Account insert RETURNING excludes transient columns`() {
        val account = Account(id = 0, email = "alice@example.com")
        val sql = account.insert().render()

        assertThat(sql).contains("returning id")
        assertThat(sql).doesNotContain("display_name")
        assertThat(sql).doesNotContain("domain")
    }

    @Test
    fun `Account update excludes transient columns`() {
        val account = Account(id = 1, email = "alice@example.com")
        val sql = account.update().render()

        assertThat(sql).contains("update accounts set email =")
        assertThat(sql).doesNotContain("display_name")
        assertThat(sql).doesNotContain("domain")
    }

    @Test
    fun `AccountAutoRowMapper maps only real columns and derives transient values`() {
        val testRow = row(
            column(0, "id", "INT8", "7"),
            column(1, "email", "TEXT", "alice@example.com")
        )

        val result = AccountAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.id).isEqualTo(7L)
        assertThat(result.email).isEqualTo("alice@example.com")
        // Transient constructor parameter falls back to its default value.
        assertThat(result.displayName).isEqualTo("alice")
        // Transient body property is derived lazily.
        assertThat(result.domain).isEqualTo("example.com")
    }
}
