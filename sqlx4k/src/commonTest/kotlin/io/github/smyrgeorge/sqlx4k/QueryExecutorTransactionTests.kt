package io.github.smyrgeorge.sqlx4k

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.utils.ControllableTransaction
import io.github.smyrgeorge.sqlx4k.utils.FakeTransactional
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class QueryExecutorTransactionTests {

    private fun withTx(tx: ControllableTransaction) = FakeTransactional { Result.success(tx) }

    @Test
    fun `transaction commits on success and returns the block value`() = runBlocking {
        val tx = ControllableTransaction()
        val result = withTx(tx).transaction { 42 }
        assertThat(result).isEqualTo(42)
        assertThat(tx.commitCount).isEqualTo(1)
        assertThat(tx.rollbackCount).isEqualTo(0)
        assertThat(tx.commited).isTrue()
    }

    @Test
    fun `transaction rolls back and rethrows when the block throws`() = runBlocking {
        val tx = ControllableTransaction()
        val ex = assertFailsWith<IllegalStateException> {
            withTx(tx).transaction { error("boom") }
        }
        assertThat(ex.message).isEqualTo("boom")
        assertThat(tx.rollbackCount).isEqualTo(1)
        assertThat(tx.commitCount).isEqualTo(0)
        assertThat(tx.rollbacked).isTrue()
    }

    @Test
    fun `transaction treats a failed Result block as failure and rolls back`() = runBlocking {
        val tx = ControllableTransaction()
        val boom = SQLError(SQLError.Code.Database, "bad")
        val ex = assertFailsWith<SQLError> {
            withTx(tx).transaction { Result.failure<Int>(boom) }
        }
        assertThat(ex).isSameInstanceAs(boom)
        assertThat(tx.rollbackCount).isEqualTo(1)
        assertThat(tx.commitCount).isEqualTo(0)
    }

    @Test
    fun `transaction commits and returns a successful Result block unchanged`() = runBlocking {
        val tx = ControllableTransaction()
        val ok = Result.success(7)
        val res = withTx(tx).transaction { ok }
        // Result is a value class, so compare by value (not reference identity).
        assertThat(res).isEqualTo(ok)
        assertThat(tx.commitCount).isEqualTo(1)
        assertThat(tx.rollbackCount).isEqualTo(0)
    }

    @Test
    fun `transaction attaches a suppressed error when rollback also fails`() = runBlocking {
        val rollbackErr = SQLError(SQLError.Code.Database, "rollback failed")
        val tx = ControllableTransaction(rollbackResult = Result.failure(rollbackErr))
        val ex = assertFailsWith<IllegalStateException> {
            withTx(tx).transaction { error("boom") }
        }
        assertThat(ex.message).isEqualTo("boom")
        val suppressed = ex.suppressedExceptions
        assertThat(suppressed.size).isEqualTo(1)
        assertThat((suppressed.first() as SQLError).code).isEqualTo(SQLError.Code.TransactionRollbackFailed)
    }

    @Test
    fun `transaction throws TransactionCommitFailed when commit fails and does not roll back`() = runBlocking {
        val commitErr = SQLError(SQLError.Code.Database, "commit failed")
        val tx = ControllableTransaction(commitResult = Result.failure(commitErr))
        val ex = assertFailsWith<SQLError> {
            withTx(tx).transaction { 1 }
        }
        assertThat(ex.code).isEqualTo(SQLError.Code.TransactionCommitFailed)
        assertThat(tx.rollbackCount).isEqualTo(0)
    }

    @Test
    fun `transaction propagates a begin failure and never runs the block`() = runBlocking {
        val db = FakeTransactional { Result.failure(SQLError(SQLError.Code.Database, "no begin")) }
        var ran = false
        assertFailsWith<SQLError> {
            db.transaction { ran = true; 1 }
        }
        assertThat(ran).isFalse()
        assertThat(db.beginCount).isEqualTo(1)
    }

    @Test
    fun `transactionCatching returns success for a successful block`() = runBlocking {
        val tx = ControllableTransaction()
        val res = withTx(tx).transactionCatching { 5 }
        assertThat(res.isSuccess).isTrue()
        assertThat(res.getOrNull()).isEqualTo(5)
        assertThat(tx.commitCount).isEqualTo(1)
    }

    @Test
    fun `transactionCatching returns failure and rolls back for a throwing block`() = runBlocking {
        val tx = ControllableTransaction()
        val res = withTx(tx).transactionCatching { error("boom") }
        assertThat(res.isFailure).isTrue()
        assertThat(tx.rollbackCount).isEqualTo(1)
    }
}
