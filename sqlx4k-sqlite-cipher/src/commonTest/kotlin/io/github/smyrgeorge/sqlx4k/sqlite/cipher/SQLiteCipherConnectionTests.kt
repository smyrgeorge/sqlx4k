@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isSuccess
import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class SQLiteCipherConnectionTests : SQLiteCipherTestBase() {

    private val db = testSQLiteCipher("SQLiteCipherConnectionTests")

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    private fun newTable(): String = "t_cn_${Random.nextInt(1_000_000)}"
    private fun countRows(table: String): Long = runBlocking {
        db.fetchAll("select count(*) from $table;").getOrThrow().first().get(0).asLong()
    }

    @Test
    fun `acquire-release should allow operations then forbid after release`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val cn: Connection = db.acquire().getOrThrow()
        // Perform operation while acquired
        assertThat(cn.execute("insert into $table(v) values (1);")).isSuccess()
        // release
        assertThat(cn.close()).isSuccess()
        // further ops should fail
        val res = cn.execute("insert into $table(v) values (2);")
        assertThat(res).isFailure()
        val ex = res.exceptionOrNull() as SQLError
        assertThat(ex.code).isEqualTo(SQLError.Code.ConnectionIsClosed)

        assertThat(countRows(table)).isEqualTo(1L)
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `close should be idempotent`(): Unit = runBlocking {
        val cn: Connection = db.acquire().getOrThrow()
        // First close should succeed
        assertThat(cn.close()).isSuccess()
        // Second close should also succeed (idempotent)
        assertThat(cn.close()).isSuccess()
        // Third close should also succeed (idempotent)
        assertThat(cn.close()).isSuccess()
    }

    @Test
    fun `connection begin-commit and rollback should work`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        // commit path
        val cn1: Connection = db.acquire().getOrThrow()
        val tx1 = cn1.begin().getOrThrow()
        assertThat(tx1.execute("insert into $table(v) values (1);")).isSuccess()
        tx1.commit().getOrThrow()
        cn1.close().getOrThrow()
        assertThat(countRows(table)).isEqualTo(1L)

        // rollback path
        val cn2: Connection = db.acquire().getOrThrow()
        val tx2 = cn2.begin().getOrThrow()
        assertThat(tx2.execute("insert into $table(v) values (2);")).isSuccess()
        tx2.rollback().getOrThrow()
        cn2.close().getOrThrow()
        assertThat(countRows(table)).isEqualTo(1L)

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `fetchAll and execute should work while acquired`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val cn: Connection = db.acquire().getOrThrow()
        assertThat(cn.execute("insert into $table(v) values (10);")).isSuccess()
        val rs = cn.fetchAll("select count(*) from $table;").getOrThrow()
        assertThat(rs.first().get(0).asLong()).isEqualTo(1L)
        cn.close().getOrThrow()

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `status should be Acquired then Released`(): Unit = runBlocking {
        val cn: Connection = db.acquire().getOrThrow()
        assertThat(cn.status).isEqualTo(Connection.Status.Open)
        cn.close().getOrThrow()
        assertThat(cn.status).isEqualTo(Connection.Status.Closed)
    }
}
