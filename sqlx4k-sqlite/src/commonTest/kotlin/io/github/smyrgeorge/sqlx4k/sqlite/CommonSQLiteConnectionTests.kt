package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isSuccess
import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

@Suppress("SqlNoDataSourceInspection")
class CommonSQLiteConnectionTests(
    private val db: ISQLite
) {

    private fun newTable(): String = "t_cn_${Random.nextInt(1_000_000)}"
    private fun countRows(table: String): Long = runBlocking {
        db.fetchAll("select count(*) from $table;").getOrThrow().first().get(0).asLong()
    }

    fun `acquire-release should allow operations then forbid after release`() = runBlocking {
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
        assertThat(ex.code).isEqualTo(SQLError.Code.ConnectionIsOpen)

        assertThat(countRows(table)).isEqualTo(1L)
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    fun `double release should fail with ConnectionIsReleased`() = runBlocking {
        val cn: Connection = db.acquire().getOrThrow()
        assertThat(cn.close()).isSuccess()
        val res = cn.close()
        assertThat(res).isFailure()
        val ex = res.exceptionOrNull() as SQLError
        assertThat(ex.code).isEqualTo(SQLError.Code.ConnectionIsOpen)
    }

    fun `connection begin-commit and rollback should work`() = runBlocking {
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

    fun `fetchAll and execute should work while acquired`() = runBlocking {
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

    fun `status should be Acquired then Released`() = runBlocking {
        val cn: Connection = db.acquire().getOrThrow()
        assertThat(cn.status).isEqualTo(Connection.Status.Open)
        cn.close().getOrThrow()
        assertThat(cn.status).isEqualTo(Connection.Status.Closed)
    }
}
