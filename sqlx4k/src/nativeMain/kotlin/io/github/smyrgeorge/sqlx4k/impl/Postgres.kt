package io.github.smyrgeorge.sqlx4k.impl

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Driver.Companion.fn
import io.github.smyrgeorge.sqlx4k.Sqlx4k
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlinx.cinterop.ExperimentalForeignApi
import librust_lib.sqlx4k_fetch_all
import librust_lib.sqlx4k_of
import librust_lib.sqlx4k_query
import librust_lib.sqlx4k_tx_begin
import librust_lib.sqlx4k_tx_commit
import librust_lib.sqlx4k_tx_fetch_all
import librust_lib.sqlx4k_tx_query
import librust_lib.sqlx4k_tx_rollback

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

        sqlx4k_of(
            host = host,
            port = port,
            username = username,
            password = password,
            database = database,
            max_connections = maxConnections
        ).throwIfError()
    }

    override suspend fun query(sql: String): Result<Unit> = runCatching {
        sqlx { idx -> sqlx4k_query(idx, sql, fn) }.throwIfError()
    }

    override suspend fun <T> fetchAll(sql: String, mapper: Sqlx4k.Row.() -> T): Result<List<T>> = runCatching {
        sqlx { idx -> sqlx4k_fetch_all(idx, sql, fn) }.map { mapper(this) }
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        val tx = sqlx { idx -> sqlx4k_tx_begin(idx, fn) }.tx()
        Tx(tx)
    }

    class Tx(override val id: Int) : Transaction {
        override suspend fun commit(): Result<Unit> = runCatching {
            sqlx { idx -> sqlx4k_tx_commit(idx, id, fn) }.throwIfError()
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            sqlx { idx -> sqlx4k_tx_rollback(idx, id, fn) }.throwIfError()
        }

        override suspend fun query(sql: String): Result<Unit> = runCatching {
            sqlx { idx -> sqlx4k_tx_query(idx, id, sql, fn) }.throwIfError()
        }

        override suspend fun <T> fetchAll(sql: String, mapper: Sqlx4k.Row.() -> T): Result<List<T>> = runCatching {
            sqlx { idx -> sqlx4k_tx_fetch_all(idx, id, sql, fn) }.map { mapper(this) }
        }
    }
}
