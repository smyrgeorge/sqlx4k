@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.mysql

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isSuccess
import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Transaction.IsolationLevel
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

class CommonMySQLConnectionTests(
    private val db: IMySQL
) {

    private fun newTable(): String = "t_cn_${Random.nextInt(1_000_000)}"
    private fun countRows(table: String): Long = runBlocking {
        db.fetchAll("select count(*) from $table;").getOrThrow().first().get(0).asLong()
    }

    fun `acquire-release should allow operations then forbid after release`() = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id int auto_increment primary key, v int not null);").getOrThrow()

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

    fun `close should be idempotent`() = runBlocking {
        val cn: Connection = db.acquire().getOrThrow()
        // First close should succeed
        assertThat(cn.close()).isSuccess()
        // Second close should also succeed (idempotent)
        assertThat(cn.close()).isSuccess()
        // Third close should also succeed (idempotent)
        assertThat(cn.close()).isSuccess()
    }

    fun `connection begin-commit and rollback should work`() = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id int auto_increment primary key, v int not null);").getOrThrow()

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
        db.execute("create table if not exists $table(id int auto_increment primary key, v int not null);").getOrThrow()

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

    fun `setTransactionIsolationLevel should work for all isolation levels`() = runBlocking {
        val cn: Connection = db.acquire().getOrThrow()

        // Test ReadCommitted
        assertThat(cn.setTransactionIsolationLevel(IsolationLevel.ReadCommitted)).isSuccess()

        // Test RepeatableRead
        assertThat(cn.setTransactionIsolationLevel(IsolationLevel.RepeatableRead)).isSuccess()

        // Test Serializable
        assertThat(cn.setTransactionIsolationLevel(IsolationLevel.Serializable)).isSuccess()

        // Test ReadUncommitted
        assertThat(cn.setTransactionIsolationLevel(IsolationLevel.ReadUncommitted)).isSuccess()

        cn.close().getOrThrow()
    }

    fun `setTransactionIsolationLevel should update the transactionIsolationLevel property`() = runBlocking {
        val cn: Connection = db.acquire().getOrThrow()

        // Initially should be null
        assertThat(cn.transactionIsolationLevel).isEqualTo(null)

        // Set isolation level and verify property is updated
        cn.setTransactionIsolationLevel(IsolationLevel.ReadCommitted).getOrThrow()
        assertThat(cn.transactionIsolationLevel).isEqualTo(IsolationLevel.ReadCommitted)

        // Change to a different level
        cn.setTransactionIsolationLevel(IsolationLevel.Serializable).getOrThrow()
        assertThat(cn.transactionIsolationLevel).isEqualTo(IsolationLevel.Serializable)

        cn.close().getOrThrow()
    }

    fun `setTransactionIsolationLevel should verify actual database isolation level`() = runBlocking {
        val cn: Connection = db.acquire().getOrThrow()

        // Set ReadCommitted and verify
        cn.setTransactionIsolationLevel(IsolationLevel.ReadCommitted).getOrThrow()
        assertThat(getCurrentIsolationLevel(cn)).isEqualTo("READ-COMMITTED")

        // Set Serializable and verify
        cn.setTransactionIsolationLevel(IsolationLevel.Serializable).getOrThrow()
        assertThat(getCurrentIsolationLevel(cn)).isEqualTo("SERIALIZABLE")

        // Set ReadUncommitted and verify
        cn.setTransactionIsolationLevel(IsolationLevel.ReadUncommitted).getOrThrow()
        assertThat(getCurrentIsolationLevel(cn)).isEqualTo("READ-UNCOMMITTED")

        // Set RepeatableRead and verify
        cn.setTransactionIsolationLevel(IsolationLevel.RepeatableRead).getOrThrow()
        assertThat(getCurrentIsolationLevel(cn)).isEqualTo("REPEATABLE-READ")

        cn.close().getOrThrow()
    }

    fun `setTransactionIsolationLevel should fail after connection is closed`() = runBlocking {
        val cn: Connection = db.acquire().getOrThrow()
        cn.close().getOrThrow()

        val result = cn.setTransactionIsolationLevel(IsolationLevel.ReadCommitted)
        assertThat(result).isFailure()
        val ex = result.exceptionOrNull() as SQLError
        assertThat(ex.code).isEqualTo(SQLError.Code.ConnectionIsClosed)
    }

    fun `connection isolation level should be reset to default after connection is closed`(db: IMySQL) = runBlocking {
        val cn: Connection = db.acquire().getOrThrow()
        assertThat(cn.transactionIsolationLevel).isEqualTo(null)

        cn.setTransactionIsolationLevel(IsolationLevel.ReadCommitted).getOrThrow()
        assertThat(cn.transactionIsolationLevel).isEqualTo(IsolationLevel.ReadCommitted)

        cn.close().getOrThrow()
        assertThat(cn.transactionIsolationLevel).isEqualTo(IMySQL.DEFAULT_TRANSACTION_ISOLATION_LEVEL)

        val cn2: Connection = db.acquire().getOrThrow()
        assertThat(cn2.transactionIsolationLevel).isEqualTo(null)
        assertThat(getCurrentIsolationLevel(cn2)).isEqualTo("REPEATABLE-READ")
        cn2.close().getOrThrow()
    }

    // Helper function to get current isolation level from database
    private suspend fun getCurrentIsolationLevel(db: QueryExecutor): String {
        // Try modern MySQL 8.0+ syntax first, fallback to older syntax
        return db.fetchAll("SELECT @@session.transaction_isolation AS isolation_level;")
            .recoverCatching { db.fetchAll("SELECT @@session.tx_isolation AS isolation_level;").getOrThrow() }
            .getOrThrow()
            .first()
            .get(0)
            .asStringOrNull() ?: ""
    }
}
