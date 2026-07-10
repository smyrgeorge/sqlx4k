package io.github.smyrgeorge.sqlx4k.impl.migrate.utils

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.test.Test
import kotlin.test.assertFailsWith

class IdentifierStringTests {

    private fun assertRejected(value: String) {
        val ex = assertFailsWith<SQLError> { IdentifierString(value) }
        assertThat(ex.code).isEqualTo(SQLError.Code.UnsafeStringContent)
    }

    // ========================================================================================
    // valid identifiers – construct OK, toString() echoes input
    // ========================================================================================

    @Test
    fun `leading underscore identifier is valid`() {
        val id = IdentifierString("_migrations")
        assertThat(id.toString()).isEqualTo("_migrations")
        assertThat(id.value).isEqualTo("_migrations")
    }

    @Test
    fun `dotted schema-qualified identifier is valid`() {
        val id = IdentifierString("public.users")
        assertThat(id.toString()).isEqualTo("public.users")
    }

    @Test
    fun `single dash is valid`() {
        val id = IdentifierString("a-b")
        assertThat(id.toString()).isEqualTo("a-b")
    }

    @Test
    fun `single slash is valid`() {
        val id = IdentifierString("a/b")
        assertThat(id.toString()).isEqualTo("a/b")
    }

    @Test
    fun `empty string is accepted`() {
        // No forbidden substring is present, so an empty identifier constructs fine.
        val id = IdentifierString("")
        assertThat(id.toString()).isEqualTo("")
    }

    // ========================================================================================
    // rejected identifiers – exact SQLError code
    // ========================================================================================

    @Test
    fun `semicolon is rejected`() {
        assertRejected("tbl;")
    }

    @Test
    fun `newline is rejected`() {
        assertRejected("a\nb")
    }

    @Test
    fun `carriage return is rejected`() {
        assertRejected("a\rb")
    }

    @Test
    fun `line comment marker is rejected`() {
        assertRejected("a--b")
    }

    @Test
    fun `block comment open marker is rejected`() {
        assertRejected("a/*b")
    }

    @Test
    fun `block comment close marker is rejected`() {
        assertRejected("a*/b")
    }

    // ========================================================================================
    // boundaries – one dash/slash allowed, the doubled comment markers rejected
    // ========================================================================================

    @Test
    fun `single dash allowed but double dash rejected`() {
        assertThat(IdentifierString("a-b").toString()).isEqualTo("a-b")
        assertRejected("a--b")
    }

    @Test
    fun `single slash allowed but slash-star rejected`() {
        assertThat(IdentifierString("a/b").toString()).isEqualTo("a/b")
        assertRejected("a/*b")
    }
}
