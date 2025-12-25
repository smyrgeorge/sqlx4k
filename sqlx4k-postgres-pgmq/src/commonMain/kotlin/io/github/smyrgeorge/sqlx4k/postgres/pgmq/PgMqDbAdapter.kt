package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import io.github.smyrgeorge.sqlx4k.postgres.Notification

/**
 * Adapter interface for interacting with PostgreSQL's message queue feature.
 *
 * This interface provides methods for listening to database notifications and for executing
 * SQL queries using prepared statements. It conforms to the `QueryExecutor` interface for
 * executing statements and retrieving results, and the `QueryExecutor.Transactional` interface
 * for managing transactions.
 */
interface PgMqDbAdapter : QueryExecutor, QueryExecutor.Transactional, Migrator.QueryExecutor {
    suspend fun listen(channel: String, f: suspend (Notification) -> Unit)
}
