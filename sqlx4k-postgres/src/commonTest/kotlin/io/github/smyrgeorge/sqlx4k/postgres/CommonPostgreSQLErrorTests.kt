package io.github.smyrgeorge.sqlx4k.postgres

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isNull
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

/**
 * Driver failures that are not database errors (TLS, refused connections, ...) must be returned as an [SQLError].
 * The native driver is built with `panic = "abort"`, so a panic on any of them would terminate the process.
 *
 * Native and JVM report these failures with different codes, so each platform passes the code it expects.
 * Native creates the pool eagerly and fails at construction, while the JVM fails on the first query.
 *
 * Database errors must also carry what the server reported (SQLSTATE and portable kind), identically on both
 * platforms.
 *
 * @param connect creates a driver for the given URL.
 */
class CommonPostgreSQLErrorTests(
    private val connect: (url: String) -> IPostgresSQL
) {

    private fun fails(url: String): Result<*> = runBlocking {
        runCatching { connect(url).fetchAll("select 1;").getOrThrow() }
    }

    fun `TLS failure should be returned as an SQLError`(expected: SQLError.Code) {
        // The test server has SSL disabled (and the native driver has no TLS support), so requiring TLS fails.
        val res = fails("postgresql://localhost:15432/test?sslmode=require")
        assertThat(res).isFailure()
        assertThat((res.exceptionOrNull() as SQLError).code).isEqualTo(expected)
    }

    fun `refused connection should be returned as an SQLError`(expected: SQLError.Code) {
        // Nothing listens on port 1.
        val res = fails("postgresql://localhost:1/test")
        assertThat(res).isFailure()
        val error = res.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(expected)
        // Not a database error: nothing was reported by a server.
        assertThat(error.sqlState).isNull()
        assertThat(error.nativeCode).isNull()
        assertThat(error.kind).isEqualTo(SQLError.Kind.Other)
    }

    fun `duplicate key should expose the driver error codes`() = runBlocking {
        val db = connect("postgresql://localhost:15432/test")
        val table = "t_err_${Random.nextInt(1_000_000)}"
        db.execute("create table $table (id int primary key);").getOrThrow()
        try {
            db.execute("insert into $table (id) values (1);").getOrThrow()
            val res = db.execute("insert into $table (id) values (1);")
            assertThat(res).isFailure()
            val error = res.exceptionOrNull() as SQLError
            assertThat(error.code).isEqualTo(SQLError.Code.Database)
            // unique_violation. PostgreSQL has no numeric error code: the SQLSTATE is the code.
            assertThat(error.sqlState).isEqualTo("23505")
            assertThat(error.nativeCode).isNull()
            assertThat(error.kind).isEqualTo(SQLError.Kind.UniqueViolation)
        } finally {
            db.execute("drop table $table;").getOrThrow()
        }
    }

    fun `unmapped database error should expose the codes`() = runBlocking {
        val db = connect("postgresql://localhost:15432/test")
        val res = db.fetchAll("select * from t_missing_${Random.nextInt(1_000_000)};")
        assertThat(res).isFailure()
        val error = res.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.Database)
        // undefined_table: neither a constraint nor a concurrency failure, so the kind is Other and the SQLSTATE tells.
        assertThat(error.sqlState).isEqualTo("42P01")
        assertThat(error.nativeCode).isNull()
        assertThat(error.kind).isEqualTo(SQLError.Kind.Other)
    }

}
