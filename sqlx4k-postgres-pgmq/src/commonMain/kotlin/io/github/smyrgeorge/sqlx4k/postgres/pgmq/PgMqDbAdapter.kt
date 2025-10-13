package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.postgres.Notification

/**
 * Adapter interface for interacting with PostgreSQL's message queue feature.
 *
 * This interface provides methods for listening to database notifications and for executing
 * SQL queries using prepared statements. It conforms to the `QueryExecutor` interface for
 * executing statements and retrieving results, and the `QueryExecutor.Transactional` interface
 * for managing transactions.
 */
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