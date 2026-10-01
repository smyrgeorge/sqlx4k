@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isNotNull
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * Driver errors that are not database errors (I/O, protocol, ...) must be returned as an [SQLError].
 * The native driver is built with `panic = "abort"`, so a panic on any of them would terminate the process.
 */
class NativeSQLiteErrorTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(1)
        .build()

    private val db = sqlite(
        url = "sqlite::memory:",
        options = options
    )

    @Test
    fun `protocol error should be returned as a Database error`() = runBlocking {
        // SQLite also accepts `@name` parameters, but sqlx only binds `?`, `?NNN` and `$NNN`,
        // so it rejects this statement with a protocol error.
        val statement = Statement.create("select ? as a, @b as b;").bind(0, 1)
        val res = db.fetchAll(statement)
        assertThat(res).isFailure()
        val error = res.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.Database)
        assertThat(error.message).isNotNull().contains("unsupported SQL parameter format")
    }
}
