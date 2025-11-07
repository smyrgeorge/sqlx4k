package io.github.smyrgeorge.sqlx4k

/**
 * Represents a transaction in the system, providing methods to manage and execute
 * transactional operations such as commit and rollback.
 *
 * This interface integrates with the `Driver` interface to facilitate execution
 * of SQL queries and retrieval of results within a transactional context.
 */
interface Transaction : QueryExecutor {
    val status: Status

    /**
     * Checks if the transaction is open and throws an error if it is closed.
     *
     * This method verifies the current status of the transaction. If the status
     * is [Status.Closed], it throws an [SQLError] indicating that the transaction
     * has already been closed.
     *
     * @throws SQLError if the transaction is closed.
     */
    fun assertIsOpen() {
        if (status != Status.Open) {
            SQLError(SQLError.Code.TransactionIsClosed, "Transaction has already been closed.").ex()
        }
    }

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

    /**
     * Represents the status of a transaction.
     *
     * The status can be either of the following:
     * - Open: Indicates that the transaction is currently active and operations can be performed.
     * - Closed: Indicates that the transaction has been finalized, either through commit or rollback.
     */
    enum class Status {
        Open,
        Closed
    }

    /**
     * Defines the isolation levels for database transactions.
     *
     * The `Isolation` enum specifies the various isolation levels that can be
     * used to control the degree of visibility between concurrent transactions.
     * These levels determine how operations in one transaction are isolated
     * from operations in other transactions. Choosing an appropriate isolation
     * level can help balance the trade-off between performance and data consistency.
     *
     * - `ReadUncommitted`: Allows a transaction to read data modified by other
     *   transactions that have not been committed yet. This level may result
     *   in dirty reads.
     * - `ReadCommitted`: Ensures that a transaction can only read data that has
     *   been committed by other transactions. This prevents dirty reads.
     * - `RepeatableRead`: Guarantees that if a transaction reads a row, the data
     *   in that row will remain consistent for the duration of the transaction.
     *   This prevents non-repeatable reads.
     * - `Serializable`: Provides the highest level of isolation by ensuring that
     *   transactions execute in a manner as if they were serialized sequentially.
     *   This prevents dirty reads, non-repeatable reads, and phantom reads, but
     *   may result in reduced concurrency.
     */
    enum class IsolationLevel(val value: String) {
        ReadUncommitted("READ UNCOMMITTED"),
        ReadCommitted("READ COMMITTED"),
        RepeatableRead("REPEATABLE READ"),
        Serializable("SERIALIZABLE"),
    }
}
