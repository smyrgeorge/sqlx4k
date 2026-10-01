package io.github.smyrgeorge.sqlx4k

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isSuccess
import assertk.assertions.isTrue
import assertk.assertions.matches
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class TransactionSavepointTests {

    /** A [Transaction] that records every `execute(sql)` call and returns a configurable result. */
    private class RecordingTransaction(
        var executeResult: Result<Long> = Result.success(0),
        /** Statements for which `execute` fails with the returned throwable instead of [executeResult]. */
        var failWhen: (String) -> Throwable? = { null },
        /** When true, `execute` suspends first, so it fails if called from a cancelled coroutine. */
        var yieldOnExecute: Boolean = false,
    ) : Transaction {
        val executed = mutableListOf<String>()
        override var status: Transaction.Status = Transaction.Status.Open
        override var commited: Boolean = false
        override var rollbacked: Boolean = false
        override val encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY

        override suspend fun commit(): Result<Unit> = Result.success(Unit).also {
            status = Transaction.Status.Closed
            commited = true
        }

        override suspend fun rollback(): Result<Unit> = Result.success(Unit).also {
            status = Transaction.Status.Closed
            rollbacked = true
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            assertIsOpen()
            if (yieldOnExecute) yield()
            executed += sql
            failWhen(sql)?.let { throw it }
            executeResult.getOrThrow()
        }

        override suspend fun execute(statement: Statement): Result<Long> = execute(statement.sql)
        override suspend fun fetchAll(sql: String): Result<ResultSet> = error("unused")
        override suspend fun fetchAll(statement: Statement): Result<ResultSet> = error("unused")
    }

    @Test
    fun `savepoint renders a SAVEPOINT statement`() = runBlocking {
        val tx = RecordingTransaction()
        assertThat(tx.savepoint("sp1")).isSuccess()
        assertThat(tx.executed).containsExactly("SAVEPOINT sp1")
    }

    @Test
    fun `releaseSavepoint renders a RELEASE SAVEPOINT statement`() = runBlocking {
        val tx = RecordingTransaction()
        assertThat(tx.releaseSavepoint("sp1")).isSuccess()
        assertThat(tx.executed).containsExactly("RELEASE SAVEPOINT sp1")
    }

    @Test
    fun `rollbackToSavepoint renders a ROLLBACK TO SAVEPOINT statement`() = runBlocking {
        val tx = RecordingTransaction()
        assertThat(tx.rollbackToSavepoint("sp1")).isSuccess()
        assertThat(tx.executed).containsExactly("ROLLBACK TO SAVEPOINT sp1")
    }

    @Test
    fun `a full savepoint sequence executes in order`() = runBlocking {
        val tx = RecordingTransaction()
        tx.savepoint("outer").getOrThrow()
        tx.savepoint("inner_2").getOrThrow()
        tx.rollbackToSavepoint("inner_2").getOrThrow()
        tx.releaseSavepoint("outer").getOrThrow()
        assertThat(tx.executed).containsExactly(
            "SAVEPOINT outer",
            "SAVEPOINT inner_2",
            "ROLLBACK TO SAVEPOINT inner_2",
            "RELEASE SAVEPOINT outer",
        )
    }

    @Test
    fun `safe names are passed through verbatim`() = runBlocking {
        val tx = RecordingTransaction()
        val names = listOf("sp", "sp1", "_sp", "Sp_1", "a_very_long_savepoint_name_42")
        names.forEach { name -> tx.savepoint(name).getOrThrow() }
        assertThat(tx.executed).isEqualTo(names.map { "SAVEPOINT $it" })
    }

    @Test
    fun `unsafe savepoint names fail with UnsafeStringContent and execute nothing`() = runBlocking {
        val tx = RecordingTransaction()
        // Names are validated with IdentifierString: semicolons, newlines and comment markers are rejected
        // before anything reaches the database. Anything else is left for the database to judge.
        val unsafe = listOf("sp;1", "sp; drop table t", "sp--x", "sp/*x", "x*/sp", "sp\n1", "sp\r1")
        unsafe.forEach { name ->
            listOf(
                tx.savepoint(name),
                tx.releaseSavepoint(name),
                tx.rollbackToSavepoint(name),
            ).forEach { res ->
                assertThat(res).isFailure()
                assertThat((res.exceptionOrNull() as SQLError).code).isEqualTo(SQLError.Code.UnsafeStringContent)
            }
        }
        assertThat(tx.executed).isEmpty()
    }

    @Test
    fun `savepoint operations on a closed transaction fail with TransactionIsClosed`() = runBlocking {
        val tx = RecordingTransaction()
        tx.commit().getOrThrow()
        listOf(tx.savepoint("sp1"), tx.releaseSavepoint("sp1"), tx.rollbackToSavepoint("sp1")).forEach { res ->
            assertThat(res).isFailure()
            assertThat((res.exceptionOrNull() as SQLError).code).isEqualTo(SQLError.Code.TransactionIsClosed)
        }
        assertThat(tx.executed).isEmpty()
    }

    @Test
    fun `a database error from execute is propagated unchanged`() = runBlocking {
        val boom = SQLError(SQLError.Code.Database, "no such savepoint: sp1")
        val tx = RecordingTransaction(executeResult = Result.failure(boom))
        val res = tx.rollbackToSavepoint("sp1")
        assertThat(res).isFailure()
        assertThat(res.exceptionOrNull()!!).isSameInstanceAs(boom)
        assertThat(tx.executed).containsExactly("ROLLBACK TO SAVEPOINT sp1")
    }

    // ---- Block helper: savepoint(name) { } ----

    @Test
    fun `savepoint block releases on success and returns the block value`() = runBlocking {
        val tx = RecordingTransaction()
        val result = tx.savepoint("sp1") {
            execute("insert into t values (1)").getOrThrow()
            42
        }
        assertThat(result).isEqualTo(Result.success(42))
        assertThat(tx.executed).containsExactly(
            "SAVEPOINT sp1",
            "insert into t values (1)",
            "RELEASE SAVEPOINT sp1",
        )
    }

    @Test
    fun `savepoint block rolls back to the savepoint and returns the error when the block throws`() = runBlocking {
        val tx = RecordingTransaction()
        val res = tx.savepoint("sp1") {
            execute("insert into t values (1)").getOrThrow()
            error("boom")
        }
        assertThat(res.exceptionOrNull()).isNotNull().isInstanceOf<IllegalStateException>()
        assertThat(res.exceptionOrNull()!!.message).isEqualTo("boom")
        assertThat(tx.executed).containsExactly(
            "SAVEPOINT sp1",
            "insert into t values (1)",
            "ROLLBACK TO SAVEPOINT sp1",
        )
        // The transaction itself is untouched.
        assertThat(tx.status).isEqualTo(Transaction.Status.Open)
        assertThat(tx.commited).isFalse()
    }

    @Test
    fun `savepoint block treats a failed Result as failure and rolls back`() = runBlocking {
        val tx = RecordingTransaction()
        val boom = SQLError(SQLError.Code.Database, "bad")
        val res = tx.savepoint("sp1") { Result.failure<Int>(boom) }
        assertThat(res.exceptionOrNull()!!).isSameInstanceAs(boom)
        assertThat(tx.executed).containsExactly("SAVEPOINT sp1", "ROLLBACK TO SAVEPOINT sp1")
    }

    @Test
    fun `savepoint block wraps a successful Result block value and releases`() = runBlocking {
        val tx = RecordingTransaction()
        val ok = Result.success(7)
        val res = tx.savepoint("sp1") { ok }
        assertThat(res).isEqualTo(Result.success(ok))
        assertThat(tx.executed).containsExactly("SAVEPOINT sp1", "RELEASE SAVEPOINT sp1")
    }

    @Test
    fun `savepoint block attaches a suppressed error when the rollback also fails`() = runBlocking {
        val rollbackErr = SQLError(SQLError.Code.Database, "rollback failed")
        val tx = RecordingTransaction(failWhen = { if (it.startsWith("ROLLBACK TO")) rollbackErr else null })
        val ex = tx.savepoint("sp1") { error("boom") }.exceptionOrNull()!!
        assertThat(ex.message).isEqualTo("boom")
        assertThat(ex.suppressedExceptions).hasSize(1)
        assertThat(ex.suppressedExceptions.single()).isSameInstanceAs(rollbackErr)
    }

    @Test
    fun `savepoint block does not run when the savepoint cannot be created`() = runBlocking {
        val createErr = SQLError(SQLError.Code.Database, "savepoints unsupported")
        val tx = RecordingTransaction(failWhen = { if (it.startsWith("SAVEPOINT")) createErr else null })
        var ran = false
        val res = tx.savepoint("sp1") { ran = true }
        assertThat(res.exceptionOrNull()!!).isSameInstanceAs(createErr)
        assertThat(ran).isFalse()
        assertThat(tx.executed).containsExactly("SAVEPOINT sp1")
    }

    @Test
    fun `savepoint block returns a failed release as a failure`() = runBlocking {
        val releaseErr = SQLError(SQLError.Code.Database, "release failed")
        val tx = RecordingTransaction(failWhen = { if (it.startsWith("RELEASE")) releaseErr else null })
        val res = tx.savepoint("sp1") { 1 }
        assertThat(res.exceptionOrNull()!!).isSameInstanceAs(releaseErr)
        assertThat(tx.executed).containsExactly("SAVEPOINT sp1", "RELEASE SAVEPOINT sp1")
    }

    @Test
    fun `savepoint block generates a distinct safe name when none is given`() = runBlocking {
        val tx = RecordingTransaction()
        tx.savepoint { 1 }.getOrThrow()
        tx.savepoint { 2 }.getOrThrow()
        val names = tx.executed.filter { it.startsWith("SAVEPOINT ") }.map { it.removePrefix("SAVEPOINT ") }
        assertThat(names).hasSize(2)
        names.forEach { assertThat(it).matches(Regex("sqlx4k_sp_[0-9a-f]+")) }
        assertThat(names[0]).isNotEqualTo(names[1])
        assertThat(tx.executed).isEqualTo(
            listOf(
                "SAVEPOINT ${names[0]}",
                "RELEASE SAVEPOINT ${names[0]}",
                "SAVEPOINT ${names[1]}",
                "RELEASE SAVEPOINT ${names[1]}"
            )
        )
    }

    @Test
    fun `nested savepoint blocks use their own names and roll back independently`() = runBlocking {
        val tx = RecordingTransaction()
        val outer = tx.savepoint("outer") {
            execute("insert 1").getOrThrow()
            val inner = savepoint("inner") { execute("insert 2").getOrThrow(); error("boom") }
            assertThat(inner).isFailure()
            execute("insert 3").getOrThrow()
        }
        assertThat(outer).isSuccess()
        assertThat(tx.executed).containsExactly(
            "SAVEPOINT outer",
            "insert 1",
            "SAVEPOINT inner",
            "insert 2",
            "ROLLBACK TO SAVEPOINT inner",
            "insert 3",
            "RELEASE SAVEPOINT outer",
        )
    }

    @Test
    fun `savepoint block on a closed transaction fails before running`() = runBlocking {
        val tx = RecordingTransaction()
        tx.commit().getOrThrow()
        var ran = false
        val res = tx.savepoint("sp1") { ran = true }
        assertThat((res.exceptionOrNull() as SQLError).code).isEqualTo(SQLError.Code.TransactionIsClosed)
        assertThat(ran).isFalse()
        assertThat(tx.executed).isEmpty()
        assertThat(tx.commited).isTrue()
    }

    // ---- Cancellation ----

    @Test
    fun `savepoint block rethrows cancellation and still rolls back to the savepoint`() = runBlocking {
        // execute() suspends, so the rollback would itself be cancelled unless it runs NonCancellable.
        val tx = RecordingTransaction(yieldOnExecute = true)
        val entered = CompletableDeferred<Unit>()
        var continued = false
        val job = launch {
            tx.savepoint("sp1") {
                entered.complete(Unit)
                awaitCancellation()
            }
            continued = true
        }
        entered.await()
        job.cancelAndJoin()
        assertThat(job.isCancelled).isTrue()
        assertThat(continued).isFalse()
        assertThat(tx.executed).containsExactly("SAVEPOINT sp1", "ROLLBACK TO SAVEPOINT sp1")
    }

    @Test
    fun `savepoint block returns a timeout inside the block as a failure`() = runBlocking {
        val tx = RecordingTransaction()
        val res = tx.savepoint("sp1") { withTimeout(10.milliseconds) { awaitCancellation() } }
        assertThat(res.exceptionOrNull()).isNotNull().isInstanceOf<TimeoutCancellationException>()
        assertThat(tx.executed).containsExactly("SAVEPOINT sp1", "ROLLBACK TO SAVEPOINT sp1")
    }
}
