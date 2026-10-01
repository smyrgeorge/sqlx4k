@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isNotNull
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.coroutines.runBlocking

/**
 * Driver errors that are not database errors (configuration, protocol, ...) must be returned as an [SQLError].
 * The Rust core is built with `panic = "abort"`, so a panic on any of them would terminate the process.
 *
 * @param open opens a database whose URL ends with the given query string (e.g. `mode=ro`).
 */
class CommonSQLiteCipherErrorTests(
    private val db: ISQLiteCipher,
    private val open: (query: String) -> ISQLiteCipher,
) {

    fun `invalid URL should be returned as a Pool error`() {
        val res = runCatching { open("mode=invalid") }
        assertThat(res).isFailure()
        val error = res.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.Pool)
        assertThat(error.message).isNotNull().contains("Invalid SQLite URL")
    }

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
