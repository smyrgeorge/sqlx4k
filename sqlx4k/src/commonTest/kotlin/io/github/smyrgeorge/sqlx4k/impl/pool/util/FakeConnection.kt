package io.github.smyrgeorge.sqlx4k.impl.pool.util

import io.github.smyrgeorge.sqlx4k.*

class FakeConnection(val id: Long) : Connection {
    override var status: Connection.Status = Connection.Status.Acquired
    var onClose: (() -> Unit)? = null
    private var closed = false
    private var releases = 0
    private var begins = 0
    private var executes = 0L

    override suspend fun release(): Result<Unit> {
        // Treat pool close or direct close identically for tests
        if (!closed) {
            closed = true
            onClose?.invoke()
        }
        status = Connection.Status.Released
        releases++
        return Result.success(Unit)
    }

    override suspend fun begin(): Result<Transaction> {
        begins++
        return Result.success(FakeTransaction())
    }

    override suspend fun execute(sql: String): Result<Long> {
        return if (sql == "id") Result.success(id) else Result.success(++executes)
    }

    override suspend fun execute(statement: Statement): Result<Long> = Result.success(++executes)
    override suspend fun fetchAll(sql: String): Result<ResultSet> =
        Result.success(ResultSet(emptyList(), null, ResultSet.Metadata(emptyList())))

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        Result.success(ResultSet(emptyList(), null, ResultSet.Metadata(emptyList())))

    override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
        Result.success(emptyList())
}