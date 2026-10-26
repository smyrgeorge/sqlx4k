@file:Suppress("SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.postgres

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

class PostgreSQLErrorTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(1)
        .acquireTimeout(1.seconds)
        .build()

    private val opened = mutableListOf<IPostgresSQL>()

    private fun connect(url: String): IPostgresSQL = testPostgreSQL(url = url, options = options).also { opened += it }

    // A driver that failed to connect may fail to close as well, so close failures are ignored.
    @AfterTest
    fun tearDown(): Unit = runBlocking { opened.forEach { it.close() } }

    private fun fails(url: String): Result<*> = runBlocking {
        runCatching { connect(url).fetchAll("select 1;").getOrThrow() }
    }

    @Test
    fun `TLS failure should be returned as an SQLError`() {
        // The test server has SSL disabled (and the native driver has no TLS support), so requiring TLS fails.
        val res = fails("postgresql://localhost:15432/test?sslmode=require")
        assertThat(res).isFailure()
        assertThat((res.exceptionOrNull() as SQLError).code).isEqualTo(tlsFailureCode)
    }

    @Test
    fun `malformed url should fail instead of crashing`() {
        // The port is not a number. The native driver used to abort the process on a URL it could not parse.
        val res = fails("postgresql://localhost:notaport/test")
        assertThat(res).isFailure()
    }

    @Test
    fun `refused connection should be returned as an SQLError`() {
        // Nothing listens on port 1.
        val res = fails("postgresql://localhost:1/test")
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

    @Test
    fun `foreign key violation should expose the driver error codes`(): Unit = runBlocking {
        val db = connect("postgresql://localhost:15432/test")
        val parent = "p_err_${Random.nextInt(1_000_000)}"
        val child = "c_err_${Random.nextInt(1_000_000)}"
        db.execute("create table $parent (id int primary key);").getOrThrow()
        db.execute("create table $child (id int primary key, parent_id int references $parent (id));").getOrThrow()
        try {
            val res = db.execute("insert into $child (id, parent_id) values (1, 42);")
            assertThat(res).isFailure()
            val error = res.exceptionOrNull() as SQLError
            assertThat(error.code).isEqualTo(SQLError.Code.Database)
            // foreign_key_violation.
            assertThat(error.sqlState).isEqualTo("23503")
            assertThat(error.nativeCode).isNull()
            assertThat(error.kind).isEqualTo(SQLError.Kind.ForeignKeyViolation)
        } finally {
            db.execute("drop table $child;").getOrThrow()
            db.execute("drop table $parent;").getOrThrow()
        }
    }

    @Test
    fun `unmapped database error should expose the codes`(): Unit = runBlocking {
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
