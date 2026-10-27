@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSuccess
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class SQLiteTransactionTests : SQLiteTestBase() {

    private val db = testSQLite("SQLiteTransactionTests")

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    // The Android platform driver rejects savepoints: android.database.sqlite intercepts `ROLLBACK TO SAVEPOINT`
    // and ends the whole transaction.
    private val savepointsRejected = testPlatform == TestPlatform.Android

    private fun newTable(): String = "t_tx_${Random.nextInt(1_000_000)}"
    private fun countRows(table: String): Long = runBlocking {
        db.fetchAll("select count(*) from $table;").getOrThrow().first().get(0).asLong()
    }

    private fun countRowsWhere(table: String, where: String): Long = runBlocking {
        db.fetchAll("select count(*) from $table where $where;").getOrThrow().first().get(0).asLong()
    }

    @Test
    fun `begin-commit should persist data`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val tx = db.begin().getOrThrow()
        assertThat(tx.execute("insert into $table(v) values (1);")).isSuccess()
        tx.commit().getOrThrow()

        assertThat(countRows(table)).isEqualTo(1L)
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `begin-rollback should revert data`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val tx = db.begin().getOrThrow()
        assertThat(tx.execute("insert into $table(v) values (1);")).isSuccess()
        tx.rollback().getOrThrow()

        assertThat(countRows(table)).isEqualTo(0L)
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `using closed transaction should fail`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val tx = db.begin().getOrThrow()
        tx.commit().getOrThrow()
        val res = tx.execute("insert into $table(v) values (2);")
        assertThat(res).isFailure()
        val ex = res.exceptionOrNull() as SQLError
        assertThat(ex.code).isEqualTo(SQLError.Code.TransactionIsClosed)

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `transaction helper should commit on success and rollback on failure`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        // success path
        val ok = runCatching {
            db.transaction {
                execute("insert into $table(v) values (1);").getOrThrow()
            }
        }
        assertThat(ok).isSuccess()
        // after commit
        assertThat(countRows(table)).isEqualTo(1L)

        // failure path - should rollback
        val err = runCatching {
            db.transaction {
                execute("insert into $table(v) values (2);").getOrThrow()
                error("boom")
            }
        }
        assertThat(err).isFailure()
        // still only the first row
        assertThat(countRows(table)).isEqualTo(1L)

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `TransactionContext new should set current and manage commit and rollback`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        // success path with TransactionContext.new
        val ok = runCatching {
            TransactionContext.new(db) {
                // currentOrNull should be non-null and equal to this
                val cur = TransactionContext.currentOrNull()
                assertThat(cur).isNotNull()
                assertThat(this === cur).isEqualTo(true)
                execute("insert into $table(v) values (1);").getOrThrow()
            }
        }
        assertThat(ok).isSuccess()
        assertThat(countRows(table)).isEqualTo(1L)

        // failure path - should rollback
        val err = runCatching {
            TransactionContext.new(db) {
                execute("insert into $table(v) values (2);").getOrThrow()
                error("boom")
            }
        }
        assertThat(err).isFailure()
        // still only one row due to rollback
        assertThat(countRows(table)).isEqualTo(1L)

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `TransactionContext withCurrent should reuse current context`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val ok = runCatching {
            TransactionContext.new(db) {
                val outer = TransactionContext.current()
                // withCurrent without db should reuse the same context
                TransactionContext.withCurrent {
                    val inner = TransactionContext.current()
                    assertThat(inner).isNotNull()
                    assertThat(inner === outer).isEqualTo(true)
                    // perform some query inside the same tx
                    execute("insert into $table(v) values (10);").getOrThrow()
                }
                // Also withCurrent(db, ...) should detect and reuse current, not start a new one
                TransactionContext.withCurrent(db) {
                    val inner2 = TransactionContext.current()
                    assertThat(inner2 === outer).isEqualTo(true)
                    execute("insert into $table(v) values (11);").getOrThrow()
                }
            }
        }
        assertThat(ok).isSuccess()
        // both inserts committed once outer tx completes
        assertThat(countRows(table)).isEqualTo(2L)

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `TransactionContext withCurrent should create new when none exists`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        // Outside any context: currentOrNull is null and current() should fail
        assertThat(runCatching { TransactionContext.current() }).isFailure()
        assertThat(runCatching { TransactionContext.currentOrNull() }.getOrNull()).isNull()

        val ok = runCatching {
            TransactionContext.withCurrent(db) {
                // Now we are inside a brand new context
                assertThat(TransactionContext.currentOrNull()).isNotNull()
                execute("insert into $table(v) values (100);").getOrThrow()
            }
        }
        assertThat(ok).isSuccess()
        assertThat(countRows(table)).isEqualTo(1L)

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `commit should be idempotent`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val tx = db.begin().getOrThrow()
        assertThat(tx.execute("insert into $table(v) values (1);")).isSuccess()

        // First commit should succeed
        assertThat(tx.commit()).isSuccess()

        // Second commit should also succeed (idempotent)
        assertThat(tx.commit()).isSuccess()

        // Third commit should also succeed (idempotent)
        assertThat(tx.commit()).isSuccess()

        assertThat(countRows(table)).isEqualTo(1L)
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `rollback should be idempotent`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val tx = db.begin().getOrThrow()
        assertThat(tx.execute("insert into $table(v) values (1);")).isSuccess()

        // First rollback should succeed
        assertThat(tx.rollback()).isSuccess()

        // Second rollback should also succeed (idempotent)
        assertThat(tx.rollback()).isSuccess()

        // Third rollback should also succeed (idempotent)
        assertThat(tx.rollback()).isSuccess()

        assertThat(countRows(table)).isEqualTo(0L)
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `commit followed by rollback should fail`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val tx = db.begin().getOrThrow()
        assertThat(tx.execute("insert into $table(v) values (1);")).isSuccess()

        // Commit should succeed
        assertThat(tx.commit()).isSuccess()

        // Rollback after commit should fail
        val res = tx.rollback()
        assertThat(res).isFailure()
        val ex = res.exceptionOrNull() as SQLError
        assertThat(ex.code).isEqualTo(SQLError.Code.TransactionIsClosed)

        assertThat(countRows(table)).isEqualTo(1L)
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `rollback followed by commit should fail`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val tx = db.begin().getOrThrow()
        assertThat(tx.execute("insert into $table(v) values (1);")).isSuccess()

        // Rollback should succeed
        assertThat(tx.rollback()).isSuccess()

        // Commit after rollback should fail
        val res = tx.commit()
        assertThat(res).isFailure()
        val ex = res.exceptionOrNull() as SQLError
        assertThat(ex.code).isEqualTo(SQLError.Code.TransactionIsClosed)

        assertThat(countRows(table)).isEqualTo(0L)
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    // ---- Savepoints ----

    @Test
    fun `savepoint rollback should revert only changes after the savepoint`(): Unit = runBlocking {
        if (savepointsRejected) return@runBlocking
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val tx = db.begin().getOrThrow()
        tx.execute("insert into $table(v) values (1);").getOrThrow()
        assertThat(tx.savepoint("sp1")).isSuccess()
        tx.execute("insert into $table(v) values (2);").getOrThrow()
        assertThat(tx.rollbackToSavepoint("sp1")).isSuccess()
        // The transaction is still open and usable after rolling back to the savepoint.
        tx.execute("insert into $table(v) values (3);").getOrThrow()
        tx.commit().getOrThrow()

        assertThat(countRows(table)).isEqualTo(2L)
        assertThat(countRowsWhere(table, "v = 2")).isEqualTo(0L)
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `release savepoint should keep changes`(): Unit = runBlocking {
        if (savepointsRejected) return@runBlocking
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val tx = db.begin().getOrThrow()
        tx.execute("insert into $table(v) values (1);").getOrThrow()
        assertThat(tx.savepoint("sp1")).isSuccess()
        tx.execute("insert into $table(v) values (2);").getOrThrow()
        assertThat(tx.releaseSavepoint("sp1")).isSuccess()
        tx.commit().getOrThrow()

        assertThat(countRows(table)).isEqualTo(2L)
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `rollback to released savepoint should fail`(): Unit = runBlocking {
        if (savepointsRejected) return@runBlocking
        val tx = db.begin().getOrThrow()
        tx.savepoint("sp1").getOrThrow()
        tx.releaseSavepoint("sp1").getOrThrow()
        // A released savepoint no longer exists. On PostgreSQL the failed statement also aborts the
        // transaction, so the test ends with a rollback rather than a commit.
        val res = tx.rollbackToSavepoint("sp1")
        assertThat(res).isFailure()
        assertThat((res.exceptionOrNull() as SQLError).code).isEqualTo(SQLError.Code.Database)
        tx.rollback().getOrThrow()
    }

    @Test
    fun `rollback to savepoint should recover from a failed statement`(): Unit = runBlocking {
        if (savepointsRejected) return@runBlocking
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val tx = db.begin().getOrThrow()
        tx.execute("insert into $table(v) values (1);").getOrThrow()
        assertThat(tx.savepoint("sp1")).isSuccess()
        // Violates the NOT NULL constraint. On PostgreSQL this aborts the transaction until a
        // ROLLBACK (TO SAVEPOINT) is issued.
        assertThat(tx.execute("insert into $table(v) values (null);")).isFailure()
        assertThat(tx.rollbackToSavepoint("sp1")).isSuccess()
        tx.execute("insert into $table(v) values (2);").getOrThrow()
        tx.commit().getOrThrow()

        assertThat(countRows(table)).isEqualTo(2L)
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `nested savepoints should roll back independently`(): Unit = runBlocking {
        if (savepointsRejected) return@runBlocking
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val tx = db.begin().getOrThrow()
        tx.execute("insert into $table(v) values (1);").getOrThrow()
        tx.savepoint("sp1").getOrThrow()
        tx.execute("insert into $table(v) values (2);").getOrThrow()
        tx.savepoint("sp2").getOrThrow()
        tx.execute("insert into $table(v) values (3);").getOrThrow()
        // Undo only the inner savepoint's work.
        tx.rollbackToSavepoint("sp2").getOrThrow()
        tx.execute("insert into $table(v) values (4);").getOrThrow()
        // Keep everything since sp1 (2 and 4).
        tx.releaseSavepoint("sp1").getOrThrow()
        tx.commit().getOrThrow()

        assertThat(countRows(table)).isEqualTo(3L)
        assertThat(countRowsWhere(table, "v = 3")).isEqualTo(0L)
        assertThat(countRowsWhere(table, "v in (1, 2, 4)")).isEqualTo(3L)
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `savepoint block should release on success and roll back on failure`(): Unit = runBlocking {
        if (savepointsRejected) return@runBlocking
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val tx = db.begin().getOrThrow()
        tx.execute("insert into $table(v) values (1);").getOrThrow()

        // success path: the block's work is kept
        val ok = tx.savepoint {
            execute("insert into $table(v) values (2);").getOrThrow()
        }
        assertThat(ok).isSuccess()

        // failure path: only the block's work is undone, the transaction stays open
        val err = tx.savepoint("failing_step") {
            execute("insert into $table(v) values (3);").getOrThrow()
            error("boom")
        }
        assertThat(err).isFailure()
        assertThat(tx.status).isEqualTo(Transaction.Status.Open)
        tx.execute("insert into $table(v) values (4);").getOrThrow()
        tx.commit().getOrThrow()

        assertThat(countRows(table)).isEqualTo(3L)
        assertThat(countRowsWhere(table, "v = 3")).isEqualTo(0L)
        assertThat(countRowsWhere(table, "v in (1, 2, 4)")).isEqualTo(3L)
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `savepoint block should recover from a failed statement`(): Unit = runBlocking {
        if (savepointsRejected) return@runBlocking
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val tx = db.begin().getOrThrow()
        tx.execute("insert into $table(v) values (1);").getOrThrow()
        // The NOT NULL violation fails the block; the helper rolls back to the savepoint, which on
        // PostgreSQL is what clears the aborted state so the transaction can continue.
        val res = tx.savepoint {
            execute("insert into $table(v) values (null);").getOrThrow()
        }
        assertThat(res).isFailure()
        tx.execute("insert into $table(v) values (2);").getOrThrow()
        tx.commit().getOrThrow()

        assertThat(countRows(table)).isEqualTo(2L)
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `nested savepoint blocks should roll back independently`(): Unit = runBlocking {
        if (savepointsRejected) return@runBlocking
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        val tx = db.begin().getOrThrow()
        tx.execute("insert into $table(v) values (1);").getOrThrow()
        tx.savepoint {
            execute("insert into $table(v) values (2);").getOrThrow()
            // inner block fails: only its insert is undone
            val inner = savepoint {
                execute("insert into $table(v) values (3);").getOrThrow()
                error("boom")
            }
            assertThat(inner).isFailure()
            execute("insert into $table(v) values (4);").getOrThrow()
        }.getOrThrow()
        tx.commit().getOrThrow()

        assertThat(countRows(table)).isEqualTo(3L)
        assertThat(countRowsWhere(table, "v = 3")).isEqualTo(0L)
        assertThat(countRowsWhere(table, "v in (1, 2, 4)")).isEqualTo(3L)
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `savepoint with unsafe name should fail`(): Unit = runBlocking {
        if (savepointsRejected) return@runBlocking
        val tx = db.begin().getOrThrow()
        // Rejected by IdentifierString before reaching the database (semicolons, comment markers, newlines).
        listOf("sp;1", "sp; drop table t", "sp--x", "sp/*x*/", "sp\n1").forEach { name ->
            val res = tx.savepoint(name)
            assertThat(res).isFailure()
            assertThat((res.exceptionOrNull() as SQLError).code).isEqualTo(SQLError.Code.UnsafeStringContent)
        }
        // Nothing was sent to the database, so the transaction is unaffected (relevant on PostgreSQL,
        // where a rejected statement would have aborted it).
        assertThat(tx.savepoint("sp_ok")).isSuccess()
        tx.rollback().getOrThrow()
    }

    @Test
    fun `savepoint on closed transaction should fail`(): Unit = runBlocking {
        if (savepointsRejected) return@runBlocking
        val tx = db.begin().getOrThrow()
        tx.commit().getOrThrow()
        listOf(tx.savepoint("sp1"), tx.releaseSavepoint("sp1"), tx.rollbackToSavepoint("sp1")).forEach { res ->
            assertThat(res).isFailure()
            assertThat((res.exceptionOrNull() as SQLError).code).isEqualTo(SQLError.Code.TransactionIsClosed)
        }
    }

    @Test
    fun `savepoints should be rejected by the driver`(): Unit = runBlocking {
        if (!savepointsRejected) return@runBlocking
        val tx = db.begin().getOrThrow()
        listOf(tx.savepoint("sp1"), tx.releaseSavepoint("sp1"), tx.rollbackToSavepoint("sp1")).forEach { res ->
            assertThat(res).isFailure()
            assertThat(res.exceptionOrNull()).isNotNull()
            assertThat((res.exceptionOrNull() as SQLError).message!!).contains("Savepoints are not supported")
        }
        // The block helper fails the same way, before running the block.
        var ran = false
        assertThat(tx.savepoint { ran = true }).isFailure()
        assertThat(ran).isEqualTo(false)
        // The transaction itself is unaffected and still usable.
        assertThat(tx.fetchAll("select 1;")).isSuccess()
        tx.commit().getOrThrow()
    }

    // A deferred foreign key is only checked when the transaction commits: the COMMIT itself fails, and that
    // failure must be reported, not turned into a successful commit.
    @Test
    fun `a commit that fails at COMMIT time is reported`(): Unit = runBlocking {
        val parent = newTable()
        val child = newTable()
        db.execute("create table $parent(id integer primary key);").getOrThrow()
        db.execute(
            "create table $child(id integer primary key, parent_id integer references $parent(id) deferrable initially deferred);"
        ).getOrThrow()

        val cn = db.acquire().getOrThrow()
        try {
            cn.execute("pragma foreign_keys = on;").getOrThrow()
            val tx = cn.begin().getOrThrow()
            // No such parent: accepted for now, rejected at COMMIT.
            tx.execute("insert into $child(id, parent_id) values (1, 42);").getOrThrow()
            val res = tx.commit()
            assertThat(res).isFailure()
            assertThat(tx.commited).isEqualTo(false)
        } finally {
            cn.close()
        }
    }

    /** A parent table, and a child one whose foreign key to it is checked only at COMMIT. */
    private suspend fun ISQLite.deferredForeignKeyTables(): Pair<String, String> {
        val parent = newTable()
        val child = newTable()
        execute("create table $parent(id integer primary key);").getOrThrow()
        execute(
            "create table $child(id integer primary key, parent_id integer references $parent(id) deferrable initially deferred);"
        ).getOrThrow()
        return parent to child
    }

    @Test
    fun `a connection whose commit failed at COMMIT time is out of the transaction`(): Unit = runBlocking {
        val (parent, child) = db.deferredForeignKeyTables()
        val cn = db.acquire().getOrThrow()
        try {
            cn.execute("pragma foreign_keys = on;").getOrThrow()
            val tx = cn.begin().getOrThrow()
            tx.execute("insert into $child(id, parent_id) values (1, 42);").getOrThrow()
            assertThat(tx.commit()).isFailure()
            // Not part of the transaction that failed: committed at once, so the other connections see it.
            cn.execute("insert into $parent(id) values (1);").getOrThrow()
            assertThat(countRows(parent)).isEqualTo(1L)
            assertThat(countRows(child)).isEqualTo(0L)
        } finally {
            cn.close()
        }
    }

    @Test
    fun `a pooled connection whose commit failed at COMMIT time goes back to the pool out of the transaction`(): Unit =
        runBlocking {
            // A single connection, so the next transaction runs on the one whose commit failed.
            val single = testSQLite(
                "SQLiteTransactionTests-single",
                ConnectionPool.Options.builder().maxConnections(1).build()
            )
            try {
                val (parent, child) = single.deferredForeignKeyTables()
                val cn = single.acquire().getOrThrow()
                try {
                    cn.execute("pragma foreign_keys = on;").getOrThrow()
                } finally {
                    cn.close().getOrThrow()
                }

                val tx = single.begin().getOrThrow()
                tx.execute("insert into $child(id, parent_id) values (1, 42);").getOrThrow()
                assertThat(tx.commit()).isFailure()

                val next = single.begin().getOrThrow()
                next.execute("insert into $parent(id) values (1);").getOrThrow()
                next.commit().getOrThrow()
                val count = "select (select count(*) from $parent) * 10 + (select count(*) from $child);"
                assertThat(single.fetchAll(count).getOrThrow().first().get(0).asLong()).isEqualTo(10L)
            } finally {
                single.close().getOrThrow()
            }
        }
}
