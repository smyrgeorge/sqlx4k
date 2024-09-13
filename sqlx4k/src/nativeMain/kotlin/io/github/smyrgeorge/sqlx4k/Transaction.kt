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

    /**
     * Commits the current transaction, finalizing all operations performed within the transaction context.
     *
     * This method ensures that all changes made during the transaction are permanently saved to the database.
     * If the commit succeeds, the transaction is completed and the connection is returned to the pool.
     * In case of an error, the transaction remains active, allowing for further actions such as rollback.
     *
     * @return A [Result] containing [Unit] if the commit was successful, or an error if the commit failed.
     */
    suspend fun commit(): Result<Unit>

    /**
     * Rolls back the current transaction, undoing all operations performed within the transaction context.
     *
     * This method ensures that all changes made during the transaction are reverted, and the transaction is not finalized.
     * Upon successful rollback, the connection is returned to the pool.
     * In case of an error, the transaction may remain in an inconsistent state and further actions might be needed to resolve it.
     *
     * @return A [Result] containing [Unit] if the rollback was successful, or an error if the rollback failed.
     */
    suspend fun rollback(): Result<Unit>
}
