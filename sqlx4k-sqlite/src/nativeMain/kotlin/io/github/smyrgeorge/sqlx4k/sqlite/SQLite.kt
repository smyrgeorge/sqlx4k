package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.sqlx
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

    override suspend fun execute(sql: String): Result<ULong> = runCatching {
        sqlx { c -> sqlx4k_query(sql, c, Driver.fn) }.rowsAffectedOrError()
    }

    override suspend fun <T> fetchAll(sql: String, mapper: ResultSet.Row.() -> T): Result<List<T>> = runCatching {
        sqlx { c -> sqlx4k_fetch_all(sql, c, Driver.fn) }.map { mapper(this) }
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        val tx = sqlx { c -> sqlx4k_tx_begin(c, Driver.fn) }.tx()
        Tx(tx.first)
    }

    class Tx(override var tx: CPointer<out CPointed>) : Transaction {
        private val mutex = Mutex()

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                sqlx { c -> sqlx4k_tx_commit(tx, c, Driver.fn) }.throwIfError()
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                sqlx { c -> sqlx4k_tx_rollback(tx, c, Driver.fn) }.throwIfError()
            }
        }

        override suspend fun execute(sql: String): Result<ULong> = runCatching {
            mutex.withLock {
                val res =
                    sqlx { c -> sqlx4k_tx_query(tx, sql, c, Driver.fn) }.tx()
                tx = res.first
                res.second
            }
        }

        override suspend fun <T> fetchAll(sql: String, mapper: ResultSet.Row.() -> T): Result<List<T>> = runCatching {
            mutex.withLock {
                sqlx { c -> sqlx4k_tx_fetch_all(tx, sql, c, Driver.fn) }
                    .txMap { mapper(this) }
                    .also { tx = it.first }
                    .second
            }
        }
    }
}
