package io.github.smyrgeorge.sqlx4k

import io.github.smyrgeorge.sqlx4k.impl.NamedParameters
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.useContents
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
    suspend fun execute(sql: String): Result<ULong>
    suspend fun execute(
        sql: String,
        params: Map<String, Any?>,
        paramsMapper: ((v: Any?) -> String?)? = null
    ): Result<ULong> = execute(NamedParameters.render(sql, params, paramsMapper))

    suspend fun fetchAll(sql: String): ResultSet
    suspend fun <T> fetchAll(sql: String, mapper: ResultSet.Row.() -> T): Result<List<T>>

    suspend fun <T> fetchAll(
        sql: String,
        params: Map<String, Any?>,
        paramsMapper: ((v: Any?) -> String?)? = null,
        mapper: ResultSet.Row.() -> T,
    ): Result<List<T>> = fetchAll(NamedParameters.render(sql, params, paramsMapper), mapper)

    /**
     * Represents a general interface for managing connection pools.
     */
    interface Pool {
        fun poolSize(): Int
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
