package io.github.smyrgeorge.sqlx4k.driver.impl

import io.github.smyrgeorge.sqlx4k.Sqlx4k
import io.github.smyrgeorge.sqlx4k.driver.Driver
import io.github.smyrgeorge.sqlx4k.driver.Driver.Companion.fn
import io.github.smyrgeorge.sqlx4k.driver.Transaction
import kotlinx.cinterop.ExperimentalForeignApi
import librust_lib.sqlx4k_fetch_all
import librust_lib.sqlx4k_of
import librust_lib.sqlx4k_query
import librust_lib.sqlx4k_tx_begin

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
        call { idx -> sqlx4k_query(idx, sql, fn) }.orThrow()
    }

    override suspend fun <T> fetchAll(sql: String, mapper: Sqlx4k.Row.() -> T): Result<List<T>> = runCatching {
        call { idx -> sqlx4k_fetch_all(idx, sql, fn) }.map { mapper(this) }
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        call { idx -> sqlx4k_tx_begin(idx, fn) }.tx()
    }
}
