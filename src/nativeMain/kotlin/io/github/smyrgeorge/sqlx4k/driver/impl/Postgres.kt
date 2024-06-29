package io.github.smyrgeorge.sqlx4k.driver.impl

import io.github.smyrgeorge.sqlx4k.Sqlx4k
import io.github.smyrgeorge.sqlx4k.driver.Driver
import io.github.smyrgeorge.sqlx4k.driver.Driver.Companion.fn
import io.github.smyrgeorge.sqlx4k.driver.Driver.Companion.idx
import io.github.smyrgeorge.sqlx4k.driver.Driver.Companion.map
import io.github.smyrgeorge.sqlx4k.driver.Driver.Companion.mutexMap
import io.github.smyrgeorge.sqlx4k.driver.Transaction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import librust_lib.Sqlx4kResult
import librust_lib.sqlx4k_fetch_all
import librust_lib.sqlx4k_of
import librust_lib.sqlx4k_query
import librust_lib.sqlx4k_tx_begin
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalForeignApi::class)
class Postgres(
    host: String,
    port: Int,
    username: String,
    password: String,
    database: String,
    maxConnections: Int
) : Driver, Driver.Tx {

    init {
        Driver.init(maxConnections)
        Transaction.init(maxConnections)

        sqlx4k_of(
            host = host,
            port = port,
            username = username,
            password = password,
            database = database,
            max_connections = maxConnections
        ).orThrow()
    }

    override suspend fun query(sql: String): Result<Unit> = runCatching {
        suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
            runBlocking {
                // The [runBlocking] it totally fine at this level.
                // We only lock for a very short period of time, just to store in the HashMap.
                val idx = idx()
                mutexMap.withLock { map[idx] = c } // Store the [Continuation] object to the HashMap.
                sqlx4k_query(idx, sql, fn)
            }
        }.orThrow()
    }

    override suspend fun <T> fetchAll(sql: String, mapper: Sqlx4k.Row.() -> T): Result<List<T>> = runCatching {
        suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
            runBlocking {
                val idx = idx()
                mutexMap.withLock { map[idx] = c }
                sqlx4k_fetch_all(idx, sql, fn)
            }
        }.map { mapper(this) }
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
            runBlocking {
                val idx = idx()
                mutexMap.withLock { map[idx] = c }
                sqlx4k_tx_begin(idx, fn)
            }
        }.tx()
    }
}
