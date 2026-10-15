package io.github.smyrgeorge.sqlx4k.mysql

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isNull
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class MySQLErrorTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(1)
        .acquireTimeout(1.seconds)
        .build()

    private val opened = mutableListOf<IMySQL>()

    private fun connect(url: String): IMySQL = testMySQL(url = url, options = options).also { opened += it }

    // A driver that failed to connect may fail to close as well, so close failures are ignored.
    @AfterTest
    fun tearDown(): Unit = runBlocking { opened.forEach { it.close() } }

    private fun fails(url: String): Result<*> = runBlocking {
        runCatching { connect(url).fetchAll("select 1;").getOrThrow() }
    }

    @Test
    fun `TLS failure should be returned as an SQLError`() {
        // The JVM driver supports TLS and so does the test server, so requiring it succeeds there.
        val expected = tlsFailureCode ?: return
        // The native driver has no TLS support, so requiring TLS fails.
        val res = fails("mysql://localhost:13306/test?ssl-mode=required")
        assertThat(res).isFailure()
        assertThat((res.exceptionOrNull() as SQLError).code).isEqualTo(expected)
    }

    @Test
    fun `refused connection should be returned as an SQLError`() {
        // Nothing listens on port 1.
        val res = fails("mysql://localhost:1/test")
        assertThat(res).isFailure()
        val error = res.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(refusedConnectionCode)
        // Not a database error: nothing was reported by a server.
        assertThat(error.sqlState).isNull()
        assertThat(error.nativeCode).isNull()
        assertThat(error.kind).isEqualTo(SQLError.Kind.Other)
    }

    @Test
    fun `duplicate key should expose the driver error codes`(): Unit = runBlocking {
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

    @Test
    fun `foreign key violation should expose the driver error codes`(): Unit = runBlocking {
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

    @Test
    fun `unmapped database error should expose the codes`(): Unit = runBlocking {
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
