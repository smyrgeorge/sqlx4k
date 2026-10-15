@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.smyrgeorge.sqlx4k.ExecuteResult
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class SQLiteExecuteResultTests : SQLiteTestBase() {

    private val db = testSQLite("SQLiteExecuteResultTests")

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    private suspend fun table(): String {
        val table = "t_exec_${Random.nextInt(1_000_000)}"
        db.execute("create table $table(id integer primary key autoincrement, v text not null);").getOrThrow()
        return table
    }

    /** Inserts two rows through [executor] and checks the reported ids against the stored ones. */
    private suspend fun insertsReportTheirIds(executor: QueryExecutor, table: String) {
        val first = executor.executeWithResult("insert into $table(v) values ('a');").getOrThrow()
        val second = executor.executeWithResult(Statement.create("insert into $table(v) values (?);").bind(0, "b"))
            .getOrThrow()
        val ids = executor.fetchAll("select id from $table order by id;").getOrThrow().map { it.get(0).asLong() }
        assertAll {
            assertThat(first).isEqualTo(ExecuteResult(rowsAffected = 1, lastInsertId = ids[0]))
            assertThat(second).isEqualTo(ExecuteResult(rowsAffected = 1, lastInsertId = ids[1]))
        }
    }

    @Test
    fun `insert reports the generated id through the pool`(): Unit = runBlocking {
        insertsReportTheirIds(db, table())
    }

    @Test
    fun `insert reports the generated id through a connection`(): Unit = runBlocking {
        val table = table()
        val cn = db.acquire().getOrThrow()
        try {
            insertsReportTheirIds(cn, table)
        } finally {
            cn.close().getOrThrow()
        }
    }

    @Test
    fun `insert reports the generated id through a transaction`(): Unit = runBlocking {
        val table = table()
        db.transaction { insertsReportTheirIds(this, table) }
    }

    @Test
    fun `multi-row insert reports the last generated id`(): Unit = runBlocking {
        val table = table()
        val res = db.executeWithResult("insert into $table(v) values ('a'), ('b'), ('c');").getOrThrow()
        val lastId = db.fetchAll("select max(id) from $table;").getOrThrow().first().get(0).asLong()
        assertThat(res).isEqualTo(ExecuteResult(rowsAffected = 3, lastInsertId = lastId))
    }

    @Test
    fun `statements that generate no id report none`(): Unit = runBlocking {
        val table = table()
        db.execute("insert into $table(v) values ('a'), ('b');").getOrThrow()
        // SQLite keeps the last inserted rowid per connection: an UPDATE must not report it.
        val update = db.executeWithResult(Statement.create("update $table set v = ?;").bind(0, "z")).getOrThrow()
        val ignored = db.executeWithResult("insert or ignore into $table(id, v) values (1, 'dup');").getOrThrow()
        assertAll {
            assertThat(update.rowsAffected).isEqualTo(2L)
            assertThat(update.lastInsertId).isNull()
            assertThat(ignored.rowsAffected).isEqualTo(0L)
            assertThat(ignored.lastInsertId).isNull()
        }
    }

    @Test
    fun `execute still reports the affected rows`(): Unit = runBlocking {
        val table = table()
        assertThat(db.execute(Statement.create("insert into $table(v) values (?), (?);").bind(0, "a").bind(1, "b"))
            .getOrThrow()).isEqualTo(2L)
    }
}
