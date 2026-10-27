package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
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

/**
 * Runs [block], a write and the read back of the row it wrote, in a single transaction: this executor itself when it
 * is one, else one begun on it (on a connection of its own, when a pool), committed when [block] succeeds and rolled
 * back when it fails.
 *
 * InnoDB keeps the rows a transaction wrote locked until it ends, so no other writer can change or delete the row
 * before it is read back. Read back after the write was committed, a concurrent writer could: a versioned update that
 * succeeded was reported as an optimistic lock failure (the row found at another version), and an insert as having
 * returned no row. The code generated for the MySQL and MariaDB dialects uses it, as MySQL has no RETURNING clause, and
 * MariaDB has none for UPDATE.
 */
suspend fun <T> QueryExecutor.withReadBackTransaction(block: suspend (QueryExecutor) -> Result<T>): Result<T> {
    if (this is Transaction) return block(this)
    return withSingleConnection { cx ->
        val transactional = cx as? QueryExecutor.Transactional ?: return@withSingleConnection block(cx)
        transactional.transactionCatching { block(this).getOrThrow() }
    }
}

