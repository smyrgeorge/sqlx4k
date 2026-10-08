package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.test.Test

/**
 * The Kotlin mapping (JDBC and Android drivers) must agree with the Rust one (`sqlx4k_sqlite_kind_of`).
 */
class SQLiteKindTests {

    @Test
    fun `constraint violations by extended code`() {
        assertThat(sqliteKindOf(2067)).isEqualTo(SQLError.Kind.UniqueViolation)
        assertThat(sqliteKindOf(1555)).isEqualTo(SQLError.Kind.UniqueViolation)
        assertThat(sqliteKindOf(787)).isEqualTo(SQLError.Kind.ForeignKeyViolation)
        assertThat(sqliteKindOf(1299)).isEqualTo(SQLError.Kind.NotNullViolation)
        assertThat(sqliteKindOf(275)).isEqualTo(SQLError.Kind.CheckViolation)
        // Plain SQLITE_CONSTRAINT (no extended code) cannot be classified.
        assertThat(sqliteKindOf(19)).isEqualTo(SQLError.Kind.Other)
    }

    @Test
    fun `lock failures by primary code including extended variants`() {
        assertThat(sqliteKindOf(5)).isEqualTo(SQLError.Kind.LockTimeout)
        assertThat(sqliteKindOf(517)).isEqualTo(SQLError.Kind.LockTimeout) // SQLITE_BUSY_SNAPSHOT
        assertThat(sqliteKindOf(6)).isEqualTo(SQLError.Kind.LockTimeout)
        assertThat(sqliteKindOf(262)).isEqualTo(SQLError.Kind.LockTimeout) // SQLITE_LOCKED_SHAREDCACHE
    }

    @Test
    fun `everything else is Other`() {
        assertThat(sqliteKindOf(1)).isEqualTo(SQLError.Kind.Other)
        assertThat(sqliteKindOf(8)).isEqualTo(SQLError.Kind.Other)
        assertThat(sqliteKindOf(null)).isEqualTo(SQLError.Kind.Other)
    }
}
