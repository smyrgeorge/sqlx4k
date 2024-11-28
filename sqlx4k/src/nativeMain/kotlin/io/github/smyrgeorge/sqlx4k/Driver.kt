package io.github.smyrgeorge.sqlx4k

import kotlinx.cinterop.*
import sqlx4k.Ptr
import sqlx4k.Sqlx4kResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * Represents an interface for executing SQL statements and managing their results.
 *
 * This interface provides methods for executing SQL queries, fetching results, and handling
 * transactions. It abstracts the underlying database operations and offers a coroutine-based
 * API for asynchronous execution.
 */
@Suppress("KDocUnresolvedReference")
@OptIn(ExperimentalForeignApi::class)
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
        val res: ResultSet = fetchAll(sql).getOrThrow()
        rowMapper.map(res)
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
        @Suppress("unused")
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
            class Builder {
                private var minConnections: Int? = null
                private var maxConnections: Int = 10
                private var acquireTimeout: Duration? = null
                private var idleTimeout: Duration? = null
                private var maxLifetime: Duration? = null

                fun minConnections(minConnections: Int) = apply { this.minConnections = minConnections }
                fun maxConnections(maxConnections: Int) = apply { this.maxConnections = maxConnections }
                fun acquireTimeout(acquireTimeout: Duration?) = apply { this.acquireTimeout = acquireTimeout }
                fun idleTimeout(idleTimeout: Duration?) = apply { this.idleTimeout = idleTimeout }
                fun maxLifetime(maxLifetime: Duration?) = apply { this.maxLifetime = maxLifetime }
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
         * Begins a new transaction asynchronously.
         *
         * This method initializes and starts a new transaction with the underlying database.
         * It suspends until the transaction has started and returns a result containing
         * the transaction instance.
         *
         * @return a result containing the started transaction
         */
        suspend fun begin(): Result<Transaction>

        /**
         * Executes a transaction with the specified transactional operations block.
         *
         * This method begins a new transaction, executes the provided block within the context
         * of the transaction, commits the transaction if the block completes successfully, and
         * rolls back the transaction in case of an error.
         *
         * @param T the return type of the transactional operations block
         * @param f the suspendable block of code containing transactional operations to be executed
         * @return the result of the transactional operations block
         * @throws Throwable if an error occurs during the transactional operations, bubble up the error after rolling back the transaction
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

    companion object {
        /**
         * A static C function pointer used with SQLx4k for handling SQL operation results.
         *
         * This function is used as a callback to process results of SQL operations executed
         * by SQLx4k. It handles the continuation of a suspended function by resuming it with
         * the result provided and properly disposing of the continuation reference.
         *
         * The function takes two parameters:
         * @param c - A pointer to the continuation that needs to be resumed.
         * @param r - A pointer to the Sqlx4kResult.
         */
        val fn = staticCFunction<CValue<Ptr>, CPointer<Sqlx4kResult>?, Unit> { c, r ->
            val ref = c.useContents { ptr }!!.asStableRef<Continuation<CPointer<Sqlx4kResult>?>>()
            ref.get().resume(r)
            ref.dispose()
        }
    }
}
