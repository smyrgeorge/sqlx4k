package io.github.smyrgeorge.sqlx4k.postgres

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.test.Test

/**
 * The JVM mapping must agree with the Rust one (`sqlx4k_postgres_kind_of`).
 */
class PostgreSQLKindTests {

    @Test
    fun `constraint violations by SQLSTATE`() {
        assertThat(postgresKindOf("23505")).isEqualTo(SQLError.Kind.UniqueViolation)
        assertThat(postgresKindOf("23503")).isEqualTo(SQLError.Kind.ForeignKeyViolation)
        assertThat(postgresKindOf("23502")).isEqualTo(SQLError.Kind.NotNullViolation)
        assertThat(postgresKindOf("23514")).isEqualTo(SQLError.Kind.CheckViolation)
        assertThat(postgresKindOf("23P01")).isEqualTo(SQLError.Kind.ExclusionViolation)
    }

    @Test
    fun `concurrency failures by SQLSTATE`() {
        assertThat(postgresKindOf("40P01")).isEqualTo(SQLError.Kind.Deadlock)
        assertThat(postgresKindOf("40001")).isEqualTo(SQLError.Kind.SerializationFailure)
        assertThat(postgresKindOf("55P03")).isEqualTo(SQLError.Kind.LockTimeout)
    }

    @Test
    fun `everything else is Other`() {
        assertThat(postgresKindOf("42P01")).isEqualTo(SQLError.Kind.Other)
        assertThat(postgresKindOf("22P02")).isEqualTo(SQLError.Kind.Other)
        assertThat(postgresKindOf(null)).isEqualTo(SQLError.Kind.Other)
    }
}
