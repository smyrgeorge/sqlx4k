package io.github.smyrgeorge.sqlx4k

import io.github.smyrgeorge.sqlx4k.impl.migrate.utils.IdentifierString
import kotlin.random.Random

/**
 * Represents a database transaction, providing methods to manage the transaction lifecycle and perform transactional operations.
 *
 * A transaction groups a series of database operations into a single unit, allowing them to succeed or fail as a whole.
 * Transactions ensure data consistency and integrity by supporting operations like commit, rollback, and savepoints.
 *
 * This interface extends [QueryExecutor], which includes the capability to execute queries within the transactional context.
 */
interface Transaction : QueryExecutor {
    val status: Status
    val commited: Boolean
    val rollbacked: Boolean

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
     * Creates a savepoint within the current transaction.
     *
     * A savepoint is a named point within a transaction that allows partial rollback
     * to the state at the savepoint without affecting changes made prior to it.
     * This method executes a SQL `SAVEPOINT` statement using the provided savepoint name.
     *
     * @param name The name of the savepoint to be created. Must be a valid SQL identifier.
     * @return A [Result] containing [Unit] if the savepoint was successfully created, or an error if the operation failed.
     * @throws SQLError if the transaction is closed or the savepoint name is invalid.
     */
    suspend fun savepoint(name: String): Result<Unit> = runCatching {
        @Suppress("SqlDialectInspection")
        execute("SAVEPOINT ${IdentifierString(name)}").getOrThrow()
    }

    /**
     * Releases a previously created savepoint within the current transaction.
     *
     * This method executes a `RELEASE SAVEPOINT` SQL command for the specified savepoint name.
     * Releasing a savepoint makes it no longer available for rolling back to
     * and can help optimize transactional resource usage.
     *
     * @param name The name of the savepoint to be released. Must be a valid SQL identifier.
     * @return A [Result] containing [Unit] if the savepoint was successfully released, or an error if the operation failed.
     */
    suspend fun releaseSavepoint(name: String): Result<Unit> = runCatching {
        @Suppress("SqlDialectInspection")
        execute("RELEASE SAVEPOINT ${IdentifierString(name)}").getOrThrow()
    }

    /**
     * Rolls back the current transaction to a previously created savepoint.
     *
     * This method undoes all changes made after the specified savepoint within the transaction context,
     * without affecting changes made prior to it. The savepoint must exist and must be valid
     * at the point of invoking this operation. If the rollback succeeds, the transaction remains
     * active and can continue making further changes.
     *
     * @param name The name of the savepoint to roll back to. Must be a valid SQL identifier.
     * @return A [Result] containing [Unit] if the rollback to the savepoint was successful, or an error if the operation failed.
     * @throws SQLError if the transaction is closed, the savepoint does not exist, or the savepoint name is invalid.
     */
    suspend fun rollbackToSavepoint(name: String): Result<Unit> = runCatching {
        @Suppress("SqlDialectInspection")
        execute("ROLLBACK TO SAVEPOINT ${IdentifierString(name)}").getOrThrow()
    }

    /**
     * Executes a block of code within a savepoint and ensures that the savepoint's lifecycle
     * is managed correctly. This includes creating the savepoint, handling exceptions, rolling
     * back to the savepoint on failure, and releasing the savepoint on completion.
     *
     * If the provided block of code [f] results in a failure (throws an exception or returns a
     * failed [Result]), the transaction is rolled back to the savepoint and the exception is propagated.
     * If the block executes successfully, the savepoint is released and the result of the block is returned.
     *
     * @param name The name of the savepoint. Defaults to a randomly generated unique name if not provided.
     *             Must be a valid SQL identifier.
     * @param f The block of code to execute within the savepoint. The receiver of this block is the current
     *          transaction.
     * @return The value returned by the block [f]. If an exception is thrown during execution or a rollback
     *         occurs to the savepoint, the exception is rethrown.
     * @throws Throwable If any errors occur during the execution of [f], rollback to the savepoint, or
     *                   release of the savepoint.
     */
    suspend fun <T> savepoint(name: String = randomSavepointName(), f: suspend Transaction.() -> T): T {
        savepoint(name).getOrThrow()
        val res = try {
            when (val r = f(this)) {
                is Result<*> if r.isFailure -> throw r.exceptionOrNull()!! // Trigger rollback
                else -> r
            }
        } catch (e: Throwable) {
            rollbackToSavepoint(name).onFailure { e.addSuppressed(it) }
            throw e
        }
        releaseSavepoint(name).getOrThrow()
        return res
    }

    /**
     * Executes a block of code within a savepoint, capturing any exceptions or failures that occur and
     * returning them as a [Result].
     *
     * This method attempts to create a savepoint using the specified [name], runs the provided block [f]
     * within the context of the savepoint, and then handles the savepoint's lifecycle. If an exception is
     * thrown or a failure occurs within [f], the transaction is rolled back to the savepoint, and the
     * resulting error is encapsulated in [Result.Failure].
     *
     * @param name The name of the savepoint. Defaults to a randomly generated unique name if not provided.
     *             Must be a valid SQL identifier.
     * @param f The block of code to execute within the savepoint. The receiver of this block is the current
     *          transaction.
     * @return A [Result] containing the success value returned by [f], or a failure if an exception was thrown
     *         or a rollback to the savepoint failed.
     */
    suspend fun <T> savepointCatching(name: String = randomSavepointName(), f: suspend Transaction.() -> T): Result<T> =
        runCatching { savepoint(name, f) }

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
     * different guarantees regarding visibility of changes made by other transactions
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

    /**
     * Generates a random name for a SQL savepoint.
     *
     * This method creates a unique name for a savepoint by appending a random,
     * hexadecimal representation of an unsigned long value to the prefix "sqlx4k_sp_".
     *
     * @return A randomly generated savepoint name in the format "sqlx4k_sp_<random_hex_value>".
     */
    private fun randomSavepointName(): String = "sqlx4k_sp_" + Random.nextLong().toULong().toString(16)
}
