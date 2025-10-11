package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.postgres.Notification

interface PgMqDbAdapter : QueryExecutor, QueryExecutor.Transactional {
    suspend fun listen(channel: String, f: suspend (Notification) -> Unit)
    override suspend fun execute(statement: Statement): Result<Long> = execute(statement.render(encoders))
    override suspend fun fetchAll(statement: Statement): Result<ResultSet> = fetchAll(statement.render(encoders))
    override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
        fetchAll(statement.render(encoders), rowMapper)

    companion object {
        private val encoders = Statement.ValueEncoderRegistry.EMPTY
    }
}