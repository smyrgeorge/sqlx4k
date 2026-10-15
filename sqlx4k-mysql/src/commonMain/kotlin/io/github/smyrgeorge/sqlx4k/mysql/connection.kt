package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs [block] on a single database connection: this executor itself when it already is one (a connection or a
 * transaction), or a connection borrowed from it for the duration of [block] when it is a pool.
 *
 * Needed when a statement depends on per-connection state left by the previous one, such as `LAST_INSERT_ID()`:
 * on a pool, two separate statements may run on different connections. The code generated for the MySQL and
 * MariaDB dialects uses it to read back the row of an INSERT or UPDATE: MySQL has no RETURNING clause, and MariaDB
 * has none for UPDATE.
 */
suspend fun <T> QueryExecutor.withSingleConnection(block: suspend (QueryExecutor) -> Result<T>): Result<T> {
    if (this !is ConnectionPool) return block(this)
    val connection = acquire().getOrElse { return Result.failure(it) }
    return try {
        block(connection)
    } finally {
        withContext(NonCancellable) { connection.close() }
    }
}
