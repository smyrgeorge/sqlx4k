package io.github.smyrgeorge.sqlx4k.impl.pool.util

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry

class FakeTransaction : Transaction {
    override var status: Transaction.Status = Transaction.Status.Open

    override suspend fun commit(): Result<Unit> {
        status = Transaction.Status.Closed
        return Result.success(Unit)
    }

    override suspend fun rollback(): Result<Unit> {
        status = Transaction.Status.Closed
        return Result.success(Unit)
    }

    override val encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY

    override suspend fun execute(sql: String): Result<Long> = Result.success(0)
    override suspend fun fetchAll(sql: String): Result<ResultSet> =
        Result.success(ResultSet(emptyList(), null, ResultSet.Metadata(emptyList())))
}