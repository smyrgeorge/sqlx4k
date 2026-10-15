@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class SQLiteCipherErrorTests : SQLiteCipherTestBase() {

    private val options = ConnectionPool.Options.builder().maxConnections(1).build()

    private val db = testSQLiteCipher("SQLiteCipherErrorTests", options = options)

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    /** Opens the suite's database with a URL that ends with the given query string (e.g. `mode=ro`). */
    private fun open(query: String): ISQLiteCipher =
        openSQLiteCipher("${testDatabaseUrl("SQLiteCipherErrorTests")}?$query", options)

    @Test
    fun `invalid URL should be returned as a Pool error`() {
        val res = runCatching { open("mode=invalid") }
        assertThat(res).isFailure()
        val error = res.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.Pool)
        assertThat(error.message).isNotNull().contains("Invalid SQLite URL")
    }

    @Test
    fun `protocol error should be returned as a Database error`(): Unit = runBlocking {
        // SQLite also accepts `@name` parameters, but sqlx only binds `?`, `?NNN` and `$NNN`,
        // so it rejects this statement with a protocol error.
        val statement = Statement.create("select ? as a, @b as b;").bind(0, 1)
        val res = db.fetchAll(statement)
        assertThat(res).isFailure()
        val error = res.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.Database)
        assertThat(error.message).isNotNull().contains("unsupported SQL parameter format")
    }

    @Test
    fun `duplicate key should expose the driver error codes`(): Unit = runBlocking {
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

    @Test
    fun `unmapped database error should expose the codes`(): Unit = runBlocking {
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
