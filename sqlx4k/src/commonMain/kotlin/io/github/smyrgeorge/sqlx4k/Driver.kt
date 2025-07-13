package io.github.smyrgeorge.sqlx4k

import kotlin.time.Duration

/**
 * Represents an interface for executing SQL statements and managing their results.
 *
 * This interface provides methods for executing SQL queries, fetching results, and handling
 * transactions. It abstracts the underlying database operations and offers a coroutine-based
 * API for asynchronous execution.
 */
interface Driver {
    /**
     * Executes the given SQL statement asynchronously.
     *
     * @param sql the SQL statement to be executed.
     * @return a result containing the number of affected rows.
     */
    suspend fun execute(sql: String): Result<Long>

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
    suspend fun fetchAll(sql: String): Result<ResultSet>

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
    suspend fun <T> fetchAll(sql: String, rowMapper: RowMapper<T>): Result<List<T>> = runCatching {
        fetchAll(sql).getOrThrow().let { rowMapper.map(it) }
    }

    /**
     * Fetches all results of the given SQL statement and maps each row using the provided RowMapper.
     *
     * @param T The type of the objects to be returned.
     * @param statement The SQL statement to be executed.
     * @param rowMapper The RowMapper to use for converting rows in the result set to instances of type T.
     * @return A Result containing a list of instances of type T mapped from the query result set.
     */
    suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>>

    /**
     * Represents a general interface for managing connection pools.
     */
    interface Pool {
        /**
         * Retrieves the current size of the connection pool.
         *
         * @return the number of connections currently in the pool
         */
        fun poolSize(): Int

        /**
         * Retrieves the number of idle connections in the connection pool.
         *
         * @return the number of idle connections in the pool
         */
        fun poolIdleSize(): Int

        /**
         * Closes the connection pool, releasing all resources.
         *
         * @return A Result object indicating the success or failure of the operation.
         */
        suspend fun close(): Result<Unit>

        /**
         * Class representing configuration options for a connection pool.
         *
         * @property minConnections The minimum number of connections to maintain at all times.
         * @property maxConnections The maximum number of connections that this pool should maintain.
         * @property acquireTimeout The maximum amount of time to spend waiting for a connection.
         * @property idleTimeout The maximum idle duration for individual connections.
         * @property maxLifetime The maximum lifetime of individual connections.
         */
        data class Options(
            // Set the minimum number of connections to maintain at all times.
            val minConnections: Int? = null,
            // Set the maximum number of connections that this pool should maintain.
            val maxConnections: Int = 10,
            // Set the maximum amount of time to spend waiting for a connection .
            val acquireTimeout: Duration? = null,
            // Set a maximum idle duration for individual connections.
            val idleTimeout: Duration? = null,
            // Set the maximum lifetime of individual connections.
            val maxLifetime: Duration? = null,
        ) {
            @Suppress("unused")
            class Builder {
                private var minConnections: Int? = null
                private var maxConnections: Int = 10
                private var acquireTimeout: Duration? = null
                private var idleTimeout: Duration? = null
                private var maxLifetime: Duration? = null

                fun minConnections(minConnections: Int) = apply { this.minConnections = minConnections }
                fun maxConnections(maxConnections: Int) = apply { this.maxConnections = maxConnections }
                fun acquireTimeout(acquireTimeout: Duration) = apply { this.acquireTimeout = acquireTimeout }
                fun idleTimeout(idleTimeout: Duration) = apply { this.idleTimeout = idleTimeout }
                fun maxLifetime(maxLifetime: Duration) = apply { this.maxLifetime = maxLifetime }
                fun build() = Options(minConnections, maxConnections, acquireTimeout, idleTimeout, maxLifetime)
            }

            companion object {
                fun builder(): Builder = Builder()
            }
        }
    }

    /**
     * Represents a transactional interface providing methods for handling transactions.
     *
     * This interface offers a method to begin a transaction. Implementers of this
     * interface are expected to handle the initialization and starting of database
     * transactions.
     */
    interface Transactional {
        /**
         * Begins (with default isolation level) a new transaction asynchronously.
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
            return try {
                val res = f(tx)
                tx.commit()
                res
            } catch (e: Throwable) {
                tx.rollback()
                throw e
            }
        }
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
         * Executes database migrations from the specified path.
         *
         * This method applies schema or data migrations to a database. It expects
         * migration files to be located in the specified directory. The default
         * path points to `./db/migrations`. The method returns a `Result` indicating
         * the success or failure of the migration process.
         *
         * @param path The file path to the directory containing migration scripts.
         *             Defaults to `./db/migrations`.
         * @return A [Result] object wrapping [Unit]. The success state indicates that
         *         the migrations were applied successfully, while a failure state
         *         contains information about the encountered error.
         */
        suspend fun migrate(path: String = "./db/migrations"): Result<Unit>
    }
}
