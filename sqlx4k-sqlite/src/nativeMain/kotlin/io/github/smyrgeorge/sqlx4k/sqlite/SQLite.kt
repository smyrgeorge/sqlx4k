package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.rowsAffectedOrError
import io.github.smyrgeorge.sqlx4k.sqlx
import io.github.smyrgeorge.sqlx4k.throwIfError
import io.github.smyrgeorge.sqlx4k.tx
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sqlx4k.sqlx4k_close
import sqlx4k.sqlx4k_fetch_all
import sqlx4k.sqlx4k_of
import sqlx4k.sqlx4k_pool_idle_size
import sqlx4k.sqlx4k_pool_size
import sqlx4k.sqlx4k_query
import sqlx4k.sqlx4k_tx_begin
import sqlx4k.sqlx4k_tx_commit
import sqlx4k.sqlx4k_tx_fetch_all
import sqlx4k.sqlx4k_tx_query
import sqlx4k.sqlx4k_tx_rollback

@Suppress("unused")
@OptIn(ExperimentalForeignApi::class)
class SQLite(
    database: String,
    maxConnections: Int
) : Driver, Driver.Pool, Driver.Transactional {
    init {
        sqlx4k_of(
            database = database,
            max_connections = maxConnections
        ).throwIfError()
    }

    override suspend fun close(): Result<Unit> = runCatching {
        sqlx { c -> sqlx4k_close(c, Driver.fn) }.throwIfError()
        Result.success(Unit)
    }

    override fun poolSize(): Int = sqlx4k_pool_size()
    override fun poolIdleSize(): Int = sqlx4k_pool_idle_size()

    override suspend fun execute(sql: String): Result<Long> = runCatching {
        sqlx { c -> sqlx4k_query(sql, c, Driver.fn) }.rowsAffectedOrError()
    }

    override suspend fun fetchAll(sql: String): Result<ResultSet> {
        val res = sqlx { c -> sqlx4k_fetch_all(sql, c, Driver.fn) }
        return ResultSet(res).toKotlinResult()
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

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                val res = sqlx { c -> sqlx4k_tx_query(tx, sql, c, Driver.fn) }.tx()
                tx = res.first
                res.second.toLong()
            }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> {
            val res = mutex.withLock {
                val r = sqlx { c -> sqlx4k_tx_fetch_all(tx, sql, c, Driver.fn) }
                ResultSet(r)
            }

            tx = res.getRaw().tx!!
            return res.toKotlinResult()
        }
    }
}
