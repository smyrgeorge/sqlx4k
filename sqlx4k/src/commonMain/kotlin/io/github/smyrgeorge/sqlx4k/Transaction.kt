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
            SQLError(SQLError.Code.TransactionIsClosed, "Transaction has already been closed.").raise()
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
     * Enum class defining the isolation levels for database transactions.
     *
     * The isolation level determines how transaction integrity is maintained and
     * how concurrent transactions interact with each other. Each level provides
     * different guarantees regarding visibility of changes made by other transactions,
     * and controls phenomena like dirty reads, non-repeatable reads, and phantom reads.
     *
     * Isolation levels:
     * - `ReadUncommitted`: Allows transactions to read data modified by other transactions that have not yet been committed.
     * - `ReadCommitted`: Ensures that only committed data is read, preventing dirty reads.
     * - `RepeatableRead`: Ensures that data read multiple times during a transaction remains consistent, preventing non-repeatable reads.
     * - `Serializable`: Provides the highest level of isolation by ensuring transactions are executed in a completely isolated manner,
     *   effectively serializing them.
     */
    enum class IsolationLevel(val value: String) {
        ReadUncommitted("READ UNCOMMITTED"),
        ReadCommitted("READ COMMITTED"),
        RepeatableRead("REPEATABLE READ"),
        Serializable("SERIALIZABLE"),
    }
}
