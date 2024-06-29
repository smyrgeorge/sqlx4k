package io.github.smyrgeorge.sqlx4k.driver

import io.github.smyrgeorge.sqlx4k.Sqlx4k
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.runBlocking
import librust_lib.Sqlx4kResult
import librust_lib.sqlx4k_tx_commit
import librust_lib.sqlx4k_tx_fetch_all
import librust_lib.sqlx4k_tx_query
import librust_lib.sqlx4k_tx_rollback
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Suppress("unused")
@OptIn(ExperimentalForeignApi::class)
class Transaction(private val id: Int) : Driver {

    suspend fun commit(): Result<Unit> = runCatching {
        suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
            runBlocking {
                arr[id] = c
                sqlx4k_tx_commit(id, fn)
            }
        }.orThrow()
    }

    suspend fun rollback(): Result<Unit> = runCatching {
        suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
            runBlocking {
                arr[id] = c
                sqlx4k_tx_rollback(id, fn)
            }
        }.orThrow()
    }

    override suspend fun query(sql: String): Result<Unit> = runCatching {
        suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
            runBlocking {
                arr[id] = c
                sqlx4k_tx_query(id, sql, fn)
            }
        }.orThrow()
    }

    override suspend fun <T> fetchAll(sql: String, mapper: Sqlx4k.Row.() -> T): Result<List<T>> = runCatching {
        suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
            runBlocking {
                arr[id] = c
                sqlx4k_tx_fetch_all(id, sql, fn)
            }
        }.map { mapper(this) }
    }

    companion object {
        private lateinit var arr: Array<Continuation<CPointer<Sqlx4kResult>?>?>
        private val fn = staticCFunction<Int, CPointer<Sqlx4kResult>?, Unit> { id, it ->
            val c = arr[id]
            arr[id] = null
            c!!.resume(it)
        }

        fun init(maxConnections: Int) {
            arr = arrayOfNulls(maxConnections)
        }
    }
}
