@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isNull
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.random.Random
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * `fetch` must stream the rows in order and in chunks, on the driver, on a connection and in a transaction;
 * release the connection when the collector completes, fails or is cancelled; and report failures as an [SQLError].
 * Identical on every platform: native (FFI) and JVM/Android (JNI) share the same Rust core.
 *
 * The platform tests build the driver with a pool of a single connection and a short acquire timeout, so a stream
 * that fails to release its connection makes the next query fail with a pool timeout instead of hanging.
 *
 * @param db a driver whose pool holds a single connection.
 */
class CommonSQLiteCipherStreamTests(private val db: ISQLiteCipher) {

    /** Seeds `rows` rows with a recursive CTE (SQLite has no `generate_series` without an extension). */
    private suspend fun seed(rows: Int): String {
        val table = "t_stream_${Random.nextInt(1_000_000)}"
        db.execute("create table $table (id integer primary key, v text not null);").getOrThrow()
        db.execute(
            """
            with recursive seq(n) as (select 1 union all select n + 1 from seq where n < $rows)
            insert into $table (id, v) select n, 'v' || n from seq;
            """.trimIndent()
        ).getOrThrow()
        return table
    }

    private suspend fun drop(table: String) {
        db.execute("drop table $table;").getOrThrow()
    }

    /** With a single pooled connection, this fails with a pool timeout if a previous stream still holds it. */
    private suspend fun assertCount(table: String, expected: Long) {
        val count = db.fetchAll("select count(*) as c from $table;").getOrThrow().first().get("c").asLong()
        assertThat(count).isEqualTo(expected)
    }

    fun `rows are streamed in order in chunks`() = runBlocking {
        val table = seed(10_000)
        try {
            val ids = db.fetch("select id, v from $table order by id;", fetchSize = 100)
                .map { it.get("id").asInt() }
                .toList()
            assertThat(ids).isEqualTo((1..10_000).toList())
            assertCount(table, 10_000)
        } finally {
            drop(table)
        }
    }

    fun `rows are streamed with a prepared statement`() = runBlocking {
        val table = seed(1_000)
        try {
            val statement = Statement.create("select id, v from $table where id > ? order by id;").bind(0, 990)
            val rows = db.fetch(statement, fetchSize = 3).toList()
            assertThat(rows.map { it.get("id").asInt() }).isEqualTo((991..1_000).toList())
            assertThat(rows.first().get("v").asString()).isEqualTo("v991")
            assertCount(table, 1_000)
        } finally {
            drop(table)
        }
    }

    fun `rows are mapped with a row mapper`() = runBlocking {
        val table = seed(100)
        try {
            val mapper = object : RowMapper<Pair<Int, String>> {
                override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): Pair<Int, String> =
                    row.get("id").asInt() to row.get("v").asString()
            }
            val rows = db.fetch("select id, v from $table order by id;", mapper, fetchSize = 7).toList()
            assertThat(rows.size).isEqualTo(100)
            assertThat(rows.first()).isEqualTo(1 to "v1")
            assertThat(rows.last()).isEqualTo(100 to "v100")
        } finally {
            drop(table)
        }
    }

    fun `an empty result completes the flow`() = runBlocking {
        val table = seed(10)
        try {
            assertThat(db.fetch("select id from $table where id > 10;").count()).isEqualTo(0)
            assertCount(table, 10)
        } finally {
            drop(table)
        }
    }

    fun `cancelling the collector releases the connection`() = runBlocking {
        val table = seed(10_000)
        try {
            val first = db.fetch("select id from $table order by id;", fetchSize = 10)
                .map { it.get("id").asInt() }
                .take(5)
                .toList()
            assertThat(first).isEqualTo(listOf(1, 2, 3, 4, 5))
            assertCount(table, 10_000)
        } finally {
            drop(table)
        }
    }

    fun `a failing query fails the collection with an SQLError`() = runBlocking {
        val res = runCatching { db.fetch("select * from t_missing_${Random.nextInt(1_000_000)};").toList() }
        assertThat(res).isFailure()
        val error = res.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.Database)
        // SQLITE_ERROR; SQLite has no SQLSTATE.
        assertThat(error.sqlState).isNull()
        assertThat(error.nativeCode).isEqualTo(1)
        // The connection is released after a failure as well.
        assertThat(db.fetchAll("select 1 as c;").getOrThrow().first().get("c").asInt()).isEqualTo(1)
    }

    fun `rows are streamed on a connection which stays usable`() = runBlocking {
        val table = seed(1_000)
        try {
            val cn = db.acquire().getOrThrow()
            try {
                assertThat(cn.fetch("select id from $table;", fetchSize = 100).count()).isEqualTo(1_000)
                // A cancelled stream resets the statement; the connection is immediately usable again.
                val first = cn.fetch("select id from $table order by id;", fetchSize = 10)
                    .map { it.get("id").asInt() }
                    .take(3)
                    .toList()
                assertThat(first).isEqualTo(listOf(1, 2, 3))
                val count = cn.fetchAll("select count(*) as c from $table;").getOrThrow().first().get("c").asLong()
                assertThat(count).isEqualTo(1_000)
            } finally {
                cn.close().getOrThrow()
            }
            assertCount(table, 1_000)
        } finally {
            drop(table)
        }
    }

    fun `rows are streamed in a transaction`() = runBlocking {
        val table = seed(1_000)
        try {
            db.transaction {
                assertThat(fetch("select id from $table;", fetchSize = 100).count()).isEqualTo(1_000)
                val first = fetch("select id from $table order by id;", fetchSize = 10)
                    .map { it.get("id").asInt() }
                    .take(3)
                    .toList()
                assertThat(first).isEqualTo(listOf(1, 2, 3))
                execute("delete from $table where id <= 500;").getOrThrow()
            }
            assertThat(db.fetch("select id from $table;").count()).isEqualTo(500)
        } finally {
            drop(table)
        }
    }
}
