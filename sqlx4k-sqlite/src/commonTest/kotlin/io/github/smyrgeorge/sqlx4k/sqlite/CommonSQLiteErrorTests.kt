@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isNull
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

/**
 * Database errors must carry what SQLite reported (the extended result code and the portable kind),
 * identically on every platform driver (native, JDBC and Android).
 *
 * @param db an open database.
 */
class CommonSQLiteErrorTests(
    private val db: ISQLite
) {

    fun `duplicate key should expose the driver error codes`() = runBlocking {
        val table = "t_err_${Random.nextInt(1_000_000)}"
        db.execute("create table $table (id integer primary key, v text unique);").getOrThrow()
        try {
            db.execute("insert into $table (id, v) values (1, 'a');").getOrThrow()
            val res = db.execute("insert into $table (id, v) values (2, 'a');")
            assertThat(res).isFailure()
            val error = res.exceptionOrNull() as SQLError
            assertThat(error.code).isEqualTo(SQLError.Code.Database)
            // SQLITE_CONSTRAINT_UNIQUE (extended result code). SQLite has no SQLSTATE.
            assertThat(error.sqlState).isNull()
            assertThat(error.nativeCode).isEqualTo(2067)
            assertThat(error.kind).isEqualTo(SQLError.Kind.UniqueViolation)
        } finally {
            db.execute("drop table $table;").getOrThrow()
        }
    }

    fun `unmapped database error should expose the codes`() = runBlocking {
        val res = db.fetchAll("select * from t_missing_${Random.nextInt(1_000_000)};")
        assertThat(res).isFailure()
        val error = res.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.Database)
        // SQLITE_ERROR: neither a constraint nor a lock failure, so the kind is Other and the code tells.
        assertThat(error.sqlState).isNull()
        assertThat(error.nativeCode).isEqualTo(1)
        assertThat(error.kind).isEqualTo(SQLError.Kind.Other)
    }

}
