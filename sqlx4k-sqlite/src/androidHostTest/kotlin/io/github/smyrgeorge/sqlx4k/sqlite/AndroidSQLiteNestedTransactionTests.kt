@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

// The Android platform driver has no savepoints: a transaction begun in another one is part of the outermost one.
class AndroidSQLiteNestedTransactionTests : SQLiteTestBase() {

    private val db = testSQLite("AndroidSQLiteNestedTransactionTests")

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    private suspend fun newTable(): String {
        val table = "t_nested_${Random.nextInt(1_000_000)}"
        db.execute("create table $table(id integer primary key);").getOrThrow()
        return table
    }

    private suspend fun count(table: String): Long =
        db.fetchAll("select count(*) from $table;").getOrThrow().first().get(0).asLong()

    @Test
    fun `a nested transaction committed is committed with the outer one`(): Unit = runBlocking {
        val table = newTable()
        val cn = db.acquire().getOrThrow()
        try {
            val outer = cn.begin().getOrThrow()
            outer.execute("insert into $table(id) values (1);").getOrThrow()
            val inner = cn.begin().getOrThrow()
            inner.execute("insert into $table(id) values (2);").getOrThrow()
            inner.commit().getOrThrow()
            outer.commit().getOrThrow()
            assertThat(count(table)).isEqualTo(2L)
        } finally {
            cn.close().getOrThrow()
        }
    }

    @Test
    fun `the commit of a transaction a nested one was rolled back in fails and rolls back`(): Unit = runBlocking {
        val table = newTable()
        val cn = db.acquire().getOrThrow()
        try {
            val outer = cn.begin().getOrThrow()
            outer.execute("insert into $table(id) values (1);").getOrThrow()
            val inner = cn.begin().getOrThrow()
            inner.execute("insert into $table(id) values (2);").getOrThrow()
            inner.rollback().getOrThrow()
            assertThat(outer.commit()).isFailure()
            assertThat(outer.commited).isEqualTo(false)
            assertThat(outer.rollbacked).isEqualTo(true)
            assertThat(count(table)).isEqualTo(0L)

            // The connection is out of the transaction.
            cn.execute("insert into $table(id) values (3);").getOrThrow()
            assertThat(count(table)).isEqualTo(1L)
        } finally {
            cn.close().getOrThrow()
        }
    }

    @Test
    fun `a transaction cannot be committed while one nested in it is open`(): Unit = runBlocking {
        val table = newTable()
        val cn = db.acquire().getOrThrow()
        try {
            val outer = cn.begin().getOrThrow()
            outer.execute("insert into $table(id) values (1);").getOrThrow()
            val inner = cn.begin().getOrThrow()
            inner.execute("insert into $table(id) values (2);").getOrThrow()
            assertThat(outer.commit()).isFailure()
            inner.commit().getOrThrow()
            outer.commit().getOrThrow()
            assertThat(count(table)).isEqualTo(2L)
        } finally {
            cn.close().getOrThrow()
        }
    }

    @Test
    fun `rolling back a transaction rolls back the one nested in it`(): Unit = runBlocking {
        val table = newTable()
        val cn = db.acquire().getOrThrow()
        try {
            val outer = cn.begin().getOrThrow()
            outer.execute("insert into $table(id) values (1);").getOrThrow()
            val inner = cn.begin().getOrThrow()
            inner.execute("insert into $table(id) values (2);").getOrThrow()
            outer.rollback().getOrThrow()
            assertThat(inner.rollbacked).isEqualTo(true)
            cn.execute("insert into $table(id) values (3);").getOrThrow()
            assertThat(count(table)).isEqualTo(1L)
        } finally {
            cn.close().getOrThrow()
        }
    }
}
