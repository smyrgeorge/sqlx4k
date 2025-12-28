package io.github.smyrgeorge.sqlx4k.sqlite

import arrow.core.Either
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSuccess
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

@Suppress("SqlNoDataSourceInspection")
class CommonSQLiteTransactionTests(
    private val db: ISQLite
) {

    private fun newTable(): String = "t_tx_${Random.nextInt(1_000_000)}"
    private fun countRows(table: String): Long = runBlocking {
        db.fetchAll("select count(*) from $table;").getOrThrow().first().get(0).asLong()
    }

    fun `begin-commit should persist data`() = runBlocking {
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

    fun `begin-rollback should revert data`() = runBlocking {
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

    fun `using closed transaction should fail`() = runBlocking {
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

    fun `transaction helper should commit on success and rollback on failure`() = runBlocking {
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

    fun `TransactionContext new should set current and manage commit and rollback`() = runBlocking {
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

    fun `TransactionContext withCurrent should reuse current context`() = runBlocking {
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

    fun `TransactionContext withCurrent should create new when none exists`() = runBlocking {
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

    fun `transaction should rollback when returning Result failure`() = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        // Insert a row successfully first
        db.execute("insert into $table(v) values (1);").getOrThrow()
        assertThat(countRows(table)).isEqualTo(1L)

        // Transaction that returns Result.failure should trigger rollback
        val err = runCatching {
            db.transaction {
                execute("insert into $table(v) values (2);").getOrThrow()
                // Return a Result.failure - this should trigger rollback
                Result.failure<Unit>(RuntimeException("Result failure test"))
            }
        }

        assertThat(err).isFailure()
        // Should still have only 1 row (the insert inside transaction was rolled back)
        assertThat(countRows(table)).isEqualTo(1L)

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    fun `transaction should rollback when returning Either Left`() = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        // Insert a row successfully first
        db.execute("insert into $table(v) values (1);").getOrThrow()
        assertThat(countRows(table)).isEqualTo(1L)

        // Transaction that returns Either.Left with Throwable should trigger rollback
        val err = runCatching {
            db.transaction {
                execute("insert into $table(v) values (2);").getOrThrow()
                // Return Either.Left with a Throwable - this should trigger rollback
                Either.Left(RuntimeException("Either.Left test"))
            }
        }

        assertThat(err).isFailure()
        // Should still have only 1 row (the insert inside transaction was rolled back)
        assertThat(countRows(table)).isEqualTo(1L)

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    fun `transaction should rollback when returning Either Left with non-Throwable`() = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        // Insert a row successfully first
        db.execute("insert into $table(v) values (1);").getOrThrow()
        assertThat(countRows(table)).isEqualTo(1L)

        // Transaction that returns Either.Left with non-Throwable should trigger rollback
        val err = runCatching {
            db.transaction {
                execute("insert into $table(v) values (2);").getOrThrow()
                // Return Either.Left with a String - this should trigger rollback and be wrapped in SQLError
                Either.Left("String error message")
            }
        }

        assertThat(err).isFailure()
        val ex = err.exceptionOrNull() as? SQLError
        assertThat(ex).isNotNull()
        assertThat(ex!!.code).isEqualTo(SQLError.Code.UknownError)
        // Should still have only 1 row (the insert inside transaction was rolled back)
        assertThat(countRows(table)).isEqualTo(1L)

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    fun `transaction should commit when returning Either Right`() = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        // Transaction that returns Either.Right should commit successfully
        val ok = runCatching {
            db.transaction {
                execute("insert into $table(v) values (1);").getOrThrow()
                // Return Either.Right - this should commit
                Either.Right("success")
            }
        }

        assertThat(ok).isSuccess()
        // Should have 1 row committed
        assertThat(countRows(table)).isEqualTo(1L)

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    fun `transaction should commit when returning successful Result`() = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);")
            .getOrThrow()

        // Transaction that returns Result.success should commit successfully
        val ok = runCatching {
            db.transaction {
                execute("insert into $table(v) values (1);").getOrThrow()
                // Return Result.success - this should commit
                Result.success("success")
            }
        }

        assertThat(ok).isSuccess()
        // Should have 1 row committed
        assertThat(countRows(table)).isEqualTo(1L)

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }
}
