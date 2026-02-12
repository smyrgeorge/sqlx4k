package io.github.smyrgeorge.sqlx4k.impl.pool

import io.github.smyrgeorge.sqlx4k.Transaction

/**
 * A wrapper around a `Transaction` that ensures proper resource management by closing the associated
 * `PooledConnection` upon completion of transactional operations such as commit or rollback.
 *
 * This class delegates transactional operations to an underlying `Transaction`, while also handling
 * the lifecycle of the pooled database connection. It guarantees that the connection is closed and
 * returned to the pool whenever a transaction is either committed or rolled back, allowing efficient
 * reuse of database connections within a connection pool.
 *
 * @constructor Creates a `PooledTransaction` instance by delegating to the provided `Transaction`
 * and associating it with the provided `PooledConnection`.
 * @param transaction The underlying transaction to which operations are delegated.
 * @param connection The pooled connection associated with the transaction.
 */
class PooledTransaction(
    private val transaction: Transaction,
    private val connection: PooledConnection
) : Transaction by transaction {
    override suspend fun commit(): Result<Unit> = runCatching {
        try {
            transaction.commit().getOrThrow()
        } finally {
            // connection.close() is internally wrapped in runCatching, so it never throws.
            // We call getOrThrow() and re-wrap with runCatching to make the intent explicit
            // and to guard against future implementations that might throw, ensuring that a
            // close failure never suppresses the original transaction exception.
            runCatching { connection.close().getOrThrow() }
        }
    }

    override suspend fun rollback(): Result<Unit> = runCatching {
        try {
            transaction.rollback().getOrThrow()
        } finally {
            runCatching { connection.close().getOrThrow() }
        }
    }
}