package io.github.smyrgeorge.sqlx4k

import io.github.smyrgeorge.sqlx4k.impl.coroutines.runSuspendCatching
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migration
import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import kotlin.time.Duration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language

/**
 * Represents an interface for executing SQL statements and managing their results.
 *
 * This interface provides methods for executing SQL queries, fetching results, and handling
 * transactions. It abstracts the underlying database operations and offers a coroutine-based
 * API for asynchronous execution.
 */
interface QueryExecutor {
    /**
     * The `ValueEncoderRegistry` instance used for encoding values supplied to SQL statements in the `PostgreSQL` class.
     * This registry maps data types to their corresponding encoders, which convert values into a format suitable for
     * inclusion in SQL queries.
     *
     * This registry is used in methods like `execute`, `fetchAll`, and other database operation methods to ensure
     * that parameters bound to SQL statements are correctly encoded before being executed.
     */
    val encoders: ValueEncoderRegistry

    /**
     * Executes the given SQL statement asynchronously.
     *
     * @param sql the SQL statement to be executed.
     * @return a result containing the number of affected rows.
     */
    suspend fun execute(@Language("SQL") sql: String): Result<Long>

    /**
     * Executes the given SQL statement asynchronously.
     *
     * @param statement the SQL statement to be executed.
     * @return a result containing the number of affected rows.
     */
    suspend fun execute(statement: Statement): Result<Long>

    /**
     * Fetches all results of the given SQL query asynchronously.
     *
     * @param sql the SQL statement to be executed.
     * @return a result containing the retrieved result set.
     */
    suspend fun fetchAll(@Language("SQL") sql: String): Result<ResultSet>

    /**
     * Fetches all results of the given SQL statement asynchronously.
     *
     * @param statement The SQL statement to be executed.
     * @return A result containing the retrieved result set.
     */
    suspend fun fetchAll(statement: Statement): Result<ResultSet>

    /**
     * Fetches all results of the given SQL query and maps each row using the provided RowMapper.
     *
     * @param T The type of the objects to be returned.
     * @param sql The SQL statement to be executed.
     * @param rowMapper The RowMapper to use for converting rows in the ResultSet to instances of type T.
     * @return A Result containing a list of instances of type T mapped from the query result set.
     */
    suspend fun <T> fetchAll(@Language("SQL") sql: String, rowMapper: RowMapper<T>): Result<List<T>> = runCatching {
        fetchAll(sql).getOrThrow().let { rowMapper.map(it, encoders) }
    }

    /**
     * Fetches all results of the given SQL statement and maps each row using the provided RowMapper.
     *
     * @param T The type of the objects to be returned.
     * @param statement The SQL statement to be executed.
     * @param rowMapper The RowMapper to use for converting rows in the result set to instances of type T.
     * @return A Result containing a list of instances of type T mapped from the query result set.
     */
    suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> = runCatching {
        fetchAll(statement).getOrThrow().let { rowMapper.map(it, encoders) }
    }

    /**
     * Streams the rows of the given SQL query as a cold [Flow].
     *
     * Unlike [fetchAll], the rows are not collected in memory: the query starts when the flow is collected, and the
     * rows are emitted as the database produces them, in chunks of [fetchSize] rows. The connection serving the query
     * is busy until the flow completes or its collector is cancelled; a flow of a [Connection] or [Transaction] holds
     * it for the whole collection, so do not issue other statements on it from inside the collector.
     *
     * A lazy flow cannot carry a [Result]: a failure, including a bad query, fails the collection with an [SQLError].
     *
     * @param sql the SQL query to be executed.
     * @param fetchSize the number of rows fetched from the driver at a time (a hint some drivers ignore).
     * @return a cold flow of the rows of the result set.
     * @throws UnsupportedOperationException if the driver does not implement streaming.
     */
    fun fetch(@Language("SQL") sql: String, fetchSize: Int = DEFAULT_FETCH_SIZE): Flow<ResultSet.Row> =
        throw UnsupportedOperationException(FETCH_NOT_SUPPORTED)

    /**
     * Streams the rows of the given SQL statement as a cold [Flow]. See [fetch].
     *
     * @param statement the SQL statement to be executed.
     * @param fetchSize the number of rows fetched from the driver at a time (a hint some drivers ignore).
     * @return a cold flow of the rows of the result set.
     * @throws UnsupportedOperationException if the driver does not implement streaming.
     */
    fun fetch(statement: Statement, fetchSize: Int = DEFAULT_FETCH_SIZE): Flow<ResultSet.Row> =
        throw UnsupportedOperationException(FETCH_NOT_SUPPORTED)

    /**
     * Streams the rows of the given SQL query, mapping each row with the provided [RowMapper]. See [fetch].
     *
     * @param T The type of the objects to be emitted.
     * @param sql the SQL query to be executed.
     * @param rowMapper the RowMapper converting each row to an instance of type T.
     * @param fetchSize the number of rows fetched from the driver at a time (a hint some drivers ignore).
     * @return a cold flow of the mapped rows.
     */
    fun <T> fetch(
        @Language("SQL") sql: String,
        rowMapper: RowMapper<T>,
        fetchSize: Int = DEFAULT_FETCH_SIZE
    ): Flow<T> = fetch(sql, fetchSize).map { rowMapper.map(it, encoders) }

    /**
     * Streams the rows of the given SQL statement, mapping each row with the provided [RowMapper]. See [fetch].
     *
     * @param T The type of the objects to be emitted.
     * @param statement the SQL statement to be executed.
     * @param rowMapper the RowMapper converting each row to an instance of type T.
     * @param fetchSize the number of rows fetched from the driver at a time (a hint some drivers ignore).
     * @return a cold flow of the mapped rows.
     */
    fun <T> fetch(
        statement: Statement,
        rowMapper: RowMapper<T>,
        fetchSize: Int = DEFAULT_FETCH_SIZE
    ): Flow<T> = fetch(statement, fetchSize).map { rowMapper.map(it, encoders) }

    /**
     * Represents a transactional interface providing methods for handling transactions.
     *
     * This interface offers a method to begin a transaction. Implementers of this
     * interface are expected to handle the initialization and starting of database
     * transactions.
     */
    interface Transactional {
        /**
         * Begins (with the default isolation level) a new transaction asynchronously.
         *
         * This method initializes and starts a new transaction with the underlying database.
         * It suspends until the transaction has started and returns a result containing
         * the transaction instance.
         *
         * @return a result containing the started transaction
         */
        suspend fun begin(): Result<Transaction>

        /**
         * Begins (with default isolation level) a transactional operation and executes the provided block of code within the transaction context.
         *
         * This method starts a new transaction, allowing the caller to perform a series of operations within
         * a transactional context. If the block of code completes successfully, the transaction is committed.
         * In case of an exception, the transaction is rolled back.
         *
         * @param T The return type of the operation executed within the transaction.
         * @param f A suspend function representing the transactional operations to be performed.
         *          It is invoked with the started transaction as the receiver.
         * @return The result of the operation performed within the transaction context.
         * @throws Throwable Rethrows any exception encountered during the execution of the transactional block.
         */
        suspend fun <T> transaction(f: suspend Transaction.() -> T): T {
            val tx: Transaction = begin().getOrThrow()
            val res = try {
                when (val r = f(tx)) {
                    is Result<*> if r.isFailure -> throw r.exceptionOrNull()!! // Trigger rollback
                    else -> r
                }
            } catch (e: Throwable) {
                // NonCancellable: if the block was cancelled, the rollback must still run (and release the connection).
                withContext(NonCancellable) { tx.rollback() }.onFailure { rollbackError ->
                    e.addSuppressed(
                        SQLError(
                            code = SQLError.Code.TransactionRollbackFailed,
                            message = rollbackError.message,
                            cause = rollbackError
                        )
                    )
                }
                throw e
            }

            // Commit outside try-catch - if it fails, we don't roll back
            tx.commit().getOrElse {
                SQLError(SQLError.Code.TransactionCommitFailed, it.message, it).raise()
            }

            return res
        }

        /**
         * Executes a transactional operation within a safe context, returning the result as a [Result].
         *
         * This method wraps the execution of a transactional block, ensuring that any exceptions or failures
         * encountered during the operation are captured and returned as part of a [Result] object. It is useful
         * for safely managing transactions without explicitly handling rollback or commit logic within the caller's context.
         *
         * Cancellation of the calling coroutine is not captured: the transaction is rolled back and the
         * [kotlinx.coroutines.CancellationException] is rethrown.
         *
         * @param T The type of the result produced by the transactional operation.
         * @param f A suspend function that defines the transactional operations to be performed.
         *          The function is executed with the started transaction as the receiver.
         * @return A [Result] containing either the successful result of the transactional block or an exception if the operation fails.
         */
        suspend fun <T> transactionCatching(f: suspend Transaction.() -> T): Result<T> =
            runSuspendCatching { transaction(f) }
    }

    /**
     * Interface representing a migration mechanism.
     *
     * This interface provides a method for applying schema or data migrations
     * to a database. Migrations typically involve executing SQL scripts or
     * statements to modify the structure of the database or its data.
     */
    interface Migrate {
        /**
         * Applies database migrations using the SQL files located in the specified path directory.
         * Uses the provided settings to manage schema creation, migration tracking, and callback operations
         * for monitoring the progress of statements and file migrations.
         *
         * @param path The directory path containing the SQL migration files. Default is "./db/migrations".
         * @param table The name of the database table used to record and track applied migrations. Default is "_sqlx4k_migrations".
         * @param schema The database schema where migrations will be applied. If null, the default schema will be used.
         * @param createSchema When true, creates the schema if it does not already exist.
         * @param afterStatementExecution A callback triggered after the execution of each SQL statement.
         * Takes a `Statement` and the execution `Duration` as arguments.
         * @param afterFileMigration A callback triggered after the migration of each SQL file.
         * Takes a `Migration` and the execution `Duration` as arguments.
         * @return A `Result` object containing the results of the migration process.
         */
        suspend fun migrate(
            path: String = "./db/migrations",
            table: String = "_sqlx4k_migrations",
            schema: String? = null, // The default schema will be used if not provided.
            createSchema: Boolean = false, // Whether to create the schema if it does not exist.
            afterStatementExecution: suspend (Statement, Duration) -> Unit = { _, _ -> },
            afterFileMigration: suspend (Migration, Duration) -> Unit = { _, _ -> }
        ): Result<Migrator.Results>

        /**
         * Applies database migrations using the supplied list of `MigrationFile` objects.
         * Utilizes the provided settings to manage schema creation, migration tracking, and callback
         * operations for monitoring the progress of individual statement executions and file migrations.
         *
         * @param supplier A function that provides a list of `MigrationFile` objects representing the SQL migration files to be applied.
         * @param table The name of the database table used to record and track migration progress. Default value is "_sqlx4k_migrations".
         * @param schema The database schema where the migrations will be applied. If null, the default schema will be used.
         * @param createSchema A boolean indicating whether to create the schema if it does not already exist. Default value is false.
         * @param afterStatementExecution A suspendable callback triggered after the execution of each SQL statement.
         *        Receives the `Statement` executed and the `Duration` of the execution as arguments. Default is a no-op.
         * @param afterFileMigration A suspendable callback triggered after the migration of each SQL file.
         *        Receives the `Migration` and the `Duration` of the file's migration as arguments. Default is a no-op.
         * @return A `Result` object containing the results of the migration process, including details of success or failure.
         */
        suspend fun migrate(
            supplier: () -> List<MigrationFile>,
            table: String = "_sqlx4k_migrations",
            schema: String? = null, // The default schema will be used if not provided.
            createSchema: Boolean = false, // Whether to create the schema if it does not exist.
            afterStatementExecution: suspend (Statement, Duration) -> Unit = { _, _ -> },
            afterFileMigration: suspend (Migration, Duration) -> Unit = { _, _ -> }
        ): Result<Migrator.Results>

        /**
         * Applies database migrations using the provided list of migration files.
         * Utilizes the specified settings to manage schema creation, migration tracking, and
         * callback operations for monitoring the progress of individual statements and file migrations.
         *
         * @param files A list of `MigrationFile` objects representing the SQL migration files to be applied.
         * @param table The name of the database table used to record and track migration progress. Default is "_sqlx4k_migrations".
         * @param schema The database schema where migrations will be applied. If null, the default schema will be used.
         * @param createSchema When true, creates the schema if it does not already exist. Default is false.
         * @param afterStatementExecution A suspendable callback triggered after the execution of each SQL statement.
         *        Receives the `Statement` executed and the `Duration` of the execution as arguments.
         * @param afterFileMigration A suspendable callback triggered after the migration of each SQL file.
         *        Receives the `Migration` and the `Duration` of the migration as arguments.
         * @return A `Result` object containing the results of the migration process, including details of success or failure.
         */
        suspend fun migrate(
            files: List<MigrationFile>,
            table: String = "_sqlx4k_migrations",
            schema: String? = null, // The default schema will be used if not provided.
            createSchema: Boolean = false, // Whether to create the schema if it does not exist.
            afterStatementExecution: suspend (Statement, Duration) -> Unit = { _, _ -> },
            afterFileMigration: suspend (Migration, Duration) -> Unit = { _, _ -> }
        ): Result<Migrator.Results>
    }

    companion object {
        /** The default number of rows [fetch] fetches from the driver at a time. */
        const val DEFAULT_FETCH_SIZE: Int = 1_000

        /** The message of the [UnsupportedOperationException] thrown by [fetch] on drivers that do not stream. */
        const val FETCH_NOT_SUPPORTED: String = "Streaming query results (fetch) is not supported by this driver."
    }
}
