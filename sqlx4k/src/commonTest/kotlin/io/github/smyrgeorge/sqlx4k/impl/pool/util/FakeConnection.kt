package io.github.smyrgeorge.sqlx4k.impl.pool.util

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction

class FakeConnection(val id: Long) : Connection {
    override var status: Connection.Status = Connection.Status.Open
    override var transactionIsolationLevel: Transaction.IsolationLevel? = null
    var onClose: (() -> Unit)? = null
    private var closed = false
    private var releases = 0
    private var begins = 0
    private var executes = 0L

    override suspend fun close(): Result<Unit> {
        // Treat pool close or direct close identically for tests
        if (!closed) {
            closed = true
            onClose?.invoke()
        }
        status = Connection.Status.Closed
        releases++
        return Result.success(Unit)
    }

    override suspend fun begin(): Result<Transaction> {
        begins++
        return Result.success(FakeTransaction())
    }

    override val encoders: Statement.ValueEncoderRegistry = Statement.ValueEncoderRegistry.EMPTY

    override suspend fun execute(sql: String): Result<Long> {
        return if (sql == "id") Result.success(id) else Result.success(++executes)
    }

    override suspend fun fetchAll(sql: String): Result<ResultSet> =
        Result.success(ResultSet(emptyList(), null, ResultSet.Metadata(emptyList())))
}