package io.github.smyrgeorge.sqlx4k.mysql

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
 * Database errors must also carry what the server reported (SQLSTATE, error number and portable kind),
 * identically on both platforms.
 *
 * @param connect creates a driver for the given URL.
 */
class CommonMySQLErrorTests(
    private val connect: (url: String) -> IMySQL
) {

    private fun fails(url: String): Result<*> = runBlocking {
        runCatching { connect(url).fetchAll("select 1;").getOrThrow() }
    }

    fun `TLS failure should be returned as an SQLError`(expected: SQLError.Code) {
        // The native driver has no TLS support, so requiring TLS fails.
        val res = fails("mysql://localhost:13306/test?ssl-mode=required")
        assertThat(res).isFailure()
        assertThat((res.exceptionOrNull() as SQLError).code).isEqualTo(expected)
    }

    fun `refused connection should be returned as an SQLError`(expected: SQLError.Code) {
        // Nothing listens on port 1.
        val res = fails("mysql://localhost:1/test")
        assertThat(res).isFailure()
        val error = res.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(expected)
        // Not a database error: nothing was reported by a server.
        assertThat(error.sqlState).isNull()
        assertThat(error.nativeCode).isNull()
        assertThat(error.kind).isEqualTo(SQLError.Kind.Other)
    }

    fun `duplicate key should expose the driver error codes`() = runBlocking {
        val db = connect("mysql://localhost:13306/test")
        val table = "t_err_${Random.nextInt(1_000_000)}"
        db.execute("create table $table (id int primary key);").getOrThrow()
        try {
            db.execute("insert into $table (id) values (1);").getOrThrow()
            val res = db.execute("insert into $table (id) values (1);")
            assertThat(res).isFailure()
            val error = res.exceptionOrNull() as SQLError
            assertThat(error.code).isEqualTo(SQLError.Code.Database)
            // ER_DUP_ENTRY. The SQLSTATE alone (23000) cannot tell it apart from a foreign key violation.
            assertThat(error.sqlState).isEqualTo("23000")
            assertThat(error.nativeCode).isEqualTo(1062)
            assertThat(error.kind).isEqualTo(SQLError.Kind.UniqueViolation)
        } finally {
            db.execute("drop table $table;").getOrThrow()
        }
    }

    fun `foreign key violation should expose the driver error codes`() = runBlocking {
        val db = connect("mysql://localhost:13306/test")
        val parent = "p_err_${Random.nextInt(1_000_000)}"
        val child = "c_err_${Random.nextInt(1_000_000)}"
        db.execute("create table $parent (id int primary key);").getOrThrow()
        db.execute(
            "create table $child (id int primary key, parent_id int, foreign key (parent_id) references $parent (id));"
        ).getOrThrow()
        try {
            val res = db.execute("insert into $child (id, parent_id) values (1, 42);")
            assertThat(res).isFailure()
            val error = res.exceptionOrNull() as SQLError
            assertThat(error.code).isEqualTo(SQLError.Code.Database)
            // ER_NO_REFERENCED_ROW_2: same SQLSTATE as a duplicate key, different error number and kind.
            assertThat(error.sqlState).isEqualTo("23000")
            assertThat(error.nativeCode).isEqualTo(1452)
            assertThat(error.kind).isEqualTo(SQLError.Kind.ForeignKeyViolation)
        } finally {
            db.execute("drop table $child;").getOrThrow()
            db.execute("drop table $parent;").getOrThrow()
        }
    }

    fun `unmapped database error should expose the codes`() = runBlocking {
        val db = connect("mysql://localhost:13306/test")
        val res = db.fetchAll("select * from t_missing_${Random.nextInt(1_000_000)};")
        assertThat(res).isFailure()
        val error = res.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.Database)
        // ER_NO_SUCH_TABLE: neither a constraint nor a concurrency failure, so the kind is Other and the codes tell.
        assertThat(error.sqlState).isEqualTo("42S02")
        assertThat(error.nativeCode).isEqualTo(1146)
        assertThat(error.kind).isEqualTo(SQLError.Kind.Other)
    }

}
