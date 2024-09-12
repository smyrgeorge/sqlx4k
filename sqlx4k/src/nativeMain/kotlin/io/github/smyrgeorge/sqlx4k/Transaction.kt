package io.github.smyrgeorge.sqlx4k

import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Represents a transaction in the system, providing methods to manage and execute
 * transactional operations such as commit and rollback.
 *
 * This interface integrates with the `Driver` interface to facilitate execution
 * of SQL queries and retrieval of results within a transactional context.
 *
 * @property tx Pointer to the underlying transaction.
 */
@Suppress("unused")
@OptIn(ExperimentalForeignApi::class)
interface Transaction : Driver {
    var tx: CPointer<out CPointed>
    suspend fun commit(): Result<Unit>
    suspend fun rollback(): Result<Unit>
}
