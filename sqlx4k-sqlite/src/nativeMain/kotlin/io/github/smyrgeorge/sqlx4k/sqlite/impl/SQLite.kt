package io.github.smyrgeorge.sqlx4k.sqlite.impl

import io.github.smyrgeorge.sqlx4k.sqlite.Driver
import io.github.smyrgeorge.sqlx4k.sqlite.Driver.Companion.fn
import io.github.smyrgeorge.sqlx4k.sqlite.Sqlx4k
import io.github.smyrgeorge.sqlx4k.sqlite.Transaction
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import librust_lib.sqlx4k_fetch_all
import librust_lib.sqlx4k_of
import librust_lib.sqlx4k_pool_idle_size
import librust_lib.sqlx4k_pool_size
import librust_lib.sqlx4k_query
import librust_lib.sqlx4k_tx_begin
import librust_lib.sqlx4k_tx_commit
import librust_lib.sqlx4k_tx_fetch_all
import librust_lib.sqlx4k_tx_query
import librust_lib.sqlx4k_tx_rollback

@Suppress("MemberVisibilityCanBePrivate")
@OptIn(ExperimentalForeignApi::class)
class SQLite(
    database: String,
    maxConnections: Int
) : Driver, Driver.Tx {
    init {
        sqlx4k_of(
            database = database,
            max_connections = maxConnections
        ).throwIfError()
    }

    fun poolSize(): Int = sqlx4k_pool_size()
    fun poolIdleSize(): Int = sqlx4k_pool_idle_size()

    override suspend fun query(sql: String): Result<Unit> = runCatching {
        sqlx { c -> sqlx4k_query(sql, c, fn) }.throwIfError()
    }

    override suspend fun <T> fetchAll(sql: String, mapper: Sqlx4k.Row.() -> T): Result<List<T>> = runCatching {
        sqlx { c -> sqlx4k_fetch_all(sql, c, fn) }.map { mapper(this) }
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        val tx = sqlx { c -> sqlx4k_tx_begin(c, fn) }.tx()
        Tx(tx)
    }

    class Tx(override var tx: CPointer<out CPointed>) : Transaction {
        private val mutex = Mutex()

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                sqlx { c -> sqlx4k_tx_commit(tx, c, fn) }.throwIfError()
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                sqlx { c -> sqlx4k_tx_rollback(tx, c, fn) }.throwIfError()
            }
        }

        override suspend fun query(sql: String): Result<Unit> = runCatching {
            mutex.withLock {
                tx = sqlx { c -> sqlx4k_tx_query(tx, sql, c, fn) }.tx()
            }
        }

        override suspend fun <T> fetchAll(sql: String, mapper: Sqlx4k.Row.() -> T): Result<List<T>> = runCatching {
            mutex.withLock {
                sqlx { c -> sqlx4k_tx_fetch_all(tx, sql, c, fn) }
                    .txMap { mapper(this) }
                    .also { tx = it.first }
                    .second
            }
        }
    }
}
