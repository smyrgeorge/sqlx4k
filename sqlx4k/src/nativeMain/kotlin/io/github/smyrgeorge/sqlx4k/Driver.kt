package io.github.smyrgeorge.sqlx4k

import kotlinx.cinterop.*
import sqlx4k.Ptr
import sqlx4k.Sqlx4kResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

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
    suspend fun execute(statement: Statement): Result<Long> =
        execute(statement.render())

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
    suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        fetchAll(statement.render())

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
