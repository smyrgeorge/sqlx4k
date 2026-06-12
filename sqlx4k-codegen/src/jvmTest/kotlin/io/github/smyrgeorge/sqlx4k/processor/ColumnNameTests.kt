package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.processor.test.generated.LegacyUserAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.delete
import io.github.smyrgeorge.sqlx4k.processor.test.generated.insert
import io.github.smyrgeorge.sqlx4k.processor.test.generated.update
import io.github.smyrgeorge.sqlx4k.processor.util.LegacyUser
import kotlin.test.Test

/**
 * Tests for explicit column names via @Column(name = ...).
 *
 * The LegacyUser entity maps:
 * - userName -> USER_NAME (explicit)
 * - email -> mail_address (explicit, update = false)
 * - isActive -> is_active (default snake_case conversion)
 */
class ColumnNameTests {

    private val user = LegacyUser(id = 1, userName = "Alice", email = "alice@example.com", isActive = true)

    // INSERT

    @Test
    fun `insert uses explicit column names`() {
        val sql = user.insert().render()

        assertThat(sql).contains("USER_NAME")
        assertThat(sql).contains("mail_address")
        assertThat(sql).doesNotContain("user_name")
        assertThat(sql).doesNotContain("email")
    }

    @Test
    fun `insert keeps default snake_case for unannotated properties`() {
        val sql = user.insert().render()

        assertThat(sql).contains("is_active")
    }

    @Test
    fun `insert targets correct table and returns id`() {
        val sql = user.insert().render()

        assertThat(sql).contains("insert into legacy_users(USER_NAME, mail_address, is_active)")
        assertThat(sql).contains("returning id")
    }

    // UPDATE

    @Test
    fun `update uses explicit column names in SET clause`() {
        val sql = user.update().render()

        assertThat(sql).contains("set USER_NAME = 'Alice', is_active = true")
    }

    @Test
    fun `update excludes non-updatable column from SET but returns it with its explicit name`() {
        val sql = user.update().render()

        assertThat(sql).doesNotContain("mail_address =")
        assertThat(sql).contains("returning id, mail_address")
    }

    @Test
    fun `update identifies row by id`() {
        val sql = user.update().render()

        assertThat(sql).contains("update legacy_users")
        assertThat(sql).contains("where id = 1")
    }

    // DELETE

    @Test
    fun `delete identifies row by id`() {
        val sql = user.delete().render()

        assertThat(sql).contains("delete from legacy_users where id = 1")
    }

    // Batch INSERT / UPDATE

    @Test
    fun `batch insert uses explicit column names`() {
        val other = user.copy(userName = "Bob", email = "bob@example.com")
        val sql = listOf(user, other).insert().render()

        assertThat(sql).contains("insert into legacy_users(USER_NAME, mail_address, is_active)")
        assertThat(sql).contains("returning id")
    }

    @Test
    fun `batch update uses explicit column names in SET and RETURNING clauses`() {
        val other = user.copy(id = 2, userName = "Bob", email = "bob@example.com")
        val sql = listOf(user, other).update().render()

        assertThat(sql).contains("set USER_NAME = v.USER_NAME, is_active = v.is_active")
        assertThat(sql).contains("returning t.id, t.mail_address")
        assertThat(sql).doesNotContain("mail_address = v.")
    }

    // RowMapper

    @Test
    fun `row mapper reads explicit and derived column names`() {
        val row = ResultSet.Row(
            listOf(
                ResultSet.Row.Column(0, "id", "INT8", "42"),
                ResultSet.Row.Column(1, "USER_NAME", "TEXT", "Carol"),
                ResultSet.Row.Column(2, "mail_address", "TEXT", "carol@example.com"),
                ResultSet.Row.Column(3, "is_active", "BOOL", "true"),
            )
        )

        val result = LegacyUserAutoRowMapper.map(row, ValueEncoderRegistry.EMPTY)

        assertThat(result.id).isEqualTo(42L)
        assertThat(result.userName).isEqualTo("Carol")
        assertThat(result.email).isEqualTo("carol@example.com")
        assertThat(result.isActive).isEqualTo(true)
    }
}
