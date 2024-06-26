package io.github.smyrgeorge.sqlx4k.driver

import io.github.smyrgeorge.sqlx4k.Sqlx4k
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import librust_lib.Sqlx4kQueryResult
import librust_lib.Sqlx4kResult
import librust_lib.sqlx4k_free_query_result
import librust_lib.sqlx4k_free_result

interface Driver {
    suspend fun query(sql: String): Result<Unit>
    suspend fun <T> fetchAll(sql: String, mapper: Sqlx4k.Row.() -> T): Result<List<T>>

    suspend fun <T> io(f: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.IO) { f() }

    @OptIn(ExperimentalForeignApi::class)
    fun CPointer<Sqlx4kResult>?.orThrow() {
        use { Sqlx4k.Error(it.error, it.error_message?.toKString()) }.throwIfError()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun <T> CPointer<Sqlx4kResult>?.use(f: (it: Sqlx4kResult) -> T): T {
        return try {
            this?.pointed?.let { f(it) }
                ?: error("Could not extract the value from the raw pointer (null).")
        } finally {
            sqlx4k_free_result(this)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun <T> CPointer<Sqlx4kQueryResult>?.use(f: (it: Sqlx4kQueryResult) -> T): T {
        return try {
            this?.pointed?.let { f(it) }
                ?: error("Could not extract the value from the raw pointer (null).")
        } finally {
            sqlx4k_free_query_result(this)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun Sqlx4kQueryResult.throwIfError(): Unit =
        Sqlx4k.Error(error, error_message?.toKString()).throwIfError()

    @OptIn(ExperimentalForeignApi::class)
    fun <T> CPointer<Sqlx4kQueryResult>?.map(f: Sqlx4k.Row.() -> T): List<T> = use { result ->
        result.throwIfError()
        val rows = mutableListOf<T>()
        repeat(result.size) { index ->
            val scope = Sqlx4k.Row(result.rows!![index])
            val row = f(scope)
            rows.add(row)
        }
        rows
    }

    interface Tx {
        suspend fun begin(): Transaction
    }
}
