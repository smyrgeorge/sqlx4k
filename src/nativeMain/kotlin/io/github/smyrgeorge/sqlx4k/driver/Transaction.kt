package io.github.smyrgeorge.sqlx4k.driver

import io.github.smyrgeorge.sqlx4k.Sqlx4k
import io.github.smyrgeorge.sqlx4k.driver.Driver.Companion.fn
import io.github.smyrgeorge.sqlx4k.driver.Driver.Companion.idx
import io.github.smyrgeorge.sqlx4k.driver.Driver.Companion.map
import io.github.smyrgeorge.sqlx4k.driver.Driver.Companion.mutexMap
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import librust_lib.Sqlx4kResult
import librust_lib.sqlx4k_tx_commit
import librust_lib.sqlx4k_tx_fetch_all
import librust_lib.sqlx4k_tx_query
import librust_lib.sqlx4k_tx_rollback
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

@Suppress("unused")
@OptIn(ExperimentalForeignApi::class)
class Transaction(private val id: Int) : Driver {

    suspend fun commit(): Result<Unit> = runCatching {
        suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
            runBlocking {
                val idx = idx()
                mutexMap.withLock { map[idx] = c }
                sqlx4k_tx_commit(idx, id, fn)
            }
        }.orThrow()
    }

    suspend fun rollback(): Result<Unit> = runCatching {
        suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
            runBlocking {
                val idx = idx()
                mutexMap.withLock { map[idx] = c }
                sqlx4k_tx_rollback(idx, id, fn)
            }
        }.orThrow()
    }

    override suspend fun query(sql: String): Result<Unit> = runCatching {
        suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
            runBlocking {
                val idx = idx()
                mutexMap.withLock { map[idx] = c }
                sqlx4k_tx_query(idx, id, sql, fn)
            }
        }.orThrow()
    }

    override suspend fun <T> fetchAll(sql: String, mapper: Sqlx4k.Row.() -> T): Result<List<T>> = runCatching {
        suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
            runBlocking {
                val idx = idx()
                mutexMap.withLock { map[idx] = c }
                sqlx4k_tx_fetch_all(idx, id, sql, fn)
            }
        }.map { mapper(this) }
    }
}
