@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.mysql

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

class MySQLExecuteResultTests {

    private val db = testMySQL()

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    private suspend fun table(): String {
        val table = "t_exec_${Random.nextInt(1_000_000)}"
        db.execute("create table $table(id bigint primary key auto_increment, v varchar(16) not null);").getOrThrow()
        return table
    }

    private suspend fun drop(table: String) {
        db.execute("drop table if exists $table;").getOrThrow()
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
        val table = table()
        try {
            insertsReportTheirIds(db, table)
        } finally {
            drop(table)
        }
    }

    @Test
    fun `insert reports the generated id through a connection`(): Unit = runBlocking {
        val table = table()
        val cn = db.acquire().getOrThrow()
        try {
            insertsReportTheirIds(cn, table)
        } finally {
            cn.close().getOrThrow()
            drop(table)
        }
    }

    @Test
    fun `insert reports the generated id through a transaction`(): Unit = runBlocking {
        val table = table()
        try {
            db.transaction { insertsReportTheirIds(this, table) }
        } finally {
            drop(table)
        }
    }

    @Test
    fun `multi-row insert reports the first generated id`(): Unit = runBlocking {
        val table = table()
        try {
            val res = db.executeWithResult("insert into $table(v) values ('a'), ('b'), ('c');").getOrThrow()
            val firstId = db.fetchAll("select min(id) from $table;").getOrThrow().first().get(0).asLong()
            assertThat(res).isEqualTo(ExecuteResult(rowsAffected = 3, lastInsertId = firstId))
        } finally {
            drop(table)
        }
    }

    @Test
    fun `statements that generate no id report none`(): Unit = runBlocking {
        val table = table()
        val plain = "t_exec_plain_${Random.nextInt(1_000_000)}"
        db.execute("create table $plain(id bigint primary key, v varchar(16) not null);").getOrThrow()
        try {
            db.execute("insert into $table(v) values ('a'), ('b');").getOrThrow()
            val update = db.executeWithResult(Statement.create("update $table set v = ?;").bind(0, "z")).getOrThrow()
            val noAutoIncrement = db.executeWithResult("insert into $plain(id, v) values (7, 'x');").getOrThrow()
            assertAll {
                assertThat(update.rowsAffected).isEqualTo(2L)
                assertThat(update.lastInsertId).isNull()
                assertThat(noAutoIncrement.rowsAffected).isEqualTo(1L)
                assertThat(noAutoIncrement.lastInsertId).isNull()
            }
        } finally {
            drop(table)
            drop(plain)
        }
    }

    @Test
    fun `execute still reports the affected rows`(): Unit = runBlocking {
        val table = table()
        try {
            assertThat(db.execute("insert into $table(v) values ('a'), ('b');").getOrThrow()).isEqualTo(2L)
        } finally {
            drop(table)
        }
    }
}
