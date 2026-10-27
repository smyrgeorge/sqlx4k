@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

// android.database.sqlite's execSQL does more than run the statement for ATTACH (it turns write-ahead logging off, so
// every connection sees the attached database) and for DDL (it drops the idle read connections and the cached
// statements, which hold the old schema).
class AndroidSQLiteSchemaStatementTests : SQLiteTestBase() {

    private val db = testSQLite(
        "AndroidSQLiteSchemaStatementTests",
        options = ConnectionPool.Options.builder().maxConnections(1).build()
    )

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    @Test
    fun `an attached database is seen by later queries`(): Unit = runBlocking {
        val other = "$testDirectory/AndroidSQLiteSchemaStatementTests-other-${Random.nextInt(1_000_000)}.db"
        db.execute("attach database '$other' as aux;").getOrThrow()
        db.execute("create table aux.t(id integer primary key);").getOrThrow()
        db.execute("insert into aux.t(id) values (1), (2);").getOrThrow()
        assertThat(db.fetchAll("select count(*) from aux.t;").getOrThrow().first().get(0).asInt()).isEqualTo(2)
    }

    @Test
    fun `a query sees the columns a table was altered to have`(): Unit = runBlocking {
        val table = "t_alter_${Random.nextInt(1_000_000)}"
        db.execute("create table $table(id integer primary key);").getOrThrow()
        db.execute("insert into $table(id) values (1);").getOrThrow()
        assertThat(db.fetchAll("select * from $table;").getOrThrow().first().size).isEqualTo(1)
        db.execute("alter table $table add column c int not null default 7;").getOrThrow()
        val row = db.fetchAll("select * from $table;").getOrThrow().first()
        assertThat(row.size).isEqualTo(2)
        assertThat(row.get("c").asInt()).isEqualTo(7)
    }
}
