package io.github.smyrgeorge.sqlx4k

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.utils.ControllableTransaction
import io.github.smyrgeorge.sqlx4k.impl.pool.util.FakeConnection
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ConnectionTransactionStateTests {

    @Test
    fun `Connection assertIsOpen passes when open`() {
        val cn = FakeConnection(1) // status defaults to Open
        cn.assertIsOpen() // must not throw
    }

    @Test
    fun `Connection assertIsOpen throws ConnectionIsClosed when closed`() {
        val cn = FakeConnection(1).apply { status = Connection.Status.Closed }
        val ex = assertFailsWith<SQLError> { cn.assertIsOpen() }
        assertThat(ex.code).isEqualTo(SQLError.Code.ConnectionIsClosed)
    }

    @Test
    fun `Transaction assertIsOpen passes when open`() {
        val tx = ControllableTransaction() // status defaults to Open
        tx.assertIsOpen() // must not throw
    }

    @Test
    fun `Transaction assertIsOpen throws TransactionIsClosed when closed`() {
        val tx = ControllableTransaction().apply { status = Transaction.Status.Closed }
        val ex = assertFailsWith<SQLError> { tx.assertIsOpen() }
        assertThat(ex.code).isEqualTo(SQLError.Code.TransactionIsClosed)
    }

    @Test
    fun `IsolationLevel maps to the expected SQL text`() {
        assertThat(Transaction.IsolationLevel.ReadUncommitted.value).isEqualTo("READ UNCOMMITTED")
        assertThat(Transaction.IsolationLevel.ReadCommitted.value).isEqualTo("READ COMMITTED")
        assertThat(Transaction.IsolationLevel.RepeatableRead.value).isEqualTo("REPEATABLE READ")
        assertThat(Transaction.IsolationLevel.Serializable.value).isEqualTo("SERIALIZABLE")
    }
}
