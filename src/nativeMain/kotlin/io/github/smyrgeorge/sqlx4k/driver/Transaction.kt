package io.github.smyrgeorge.sqlx4k.driver

import io.github.smyrgeorge.sqlx4k.Sqlx4k
import io.github.smyrgeorge.sqlx4k.driver.Driver.Companion.fn
import io.github.smyrgeorge.sqlx4k.driver.impl.sqlx
import kotlinx.cinterop.ExperimentalForeignApi
import librust_lib.sqlx4k_tx_commit
import librust_lib.sqlx4k_tx_fetch_all
import librust_lib.sqlx4k_tx_query
import librust_lib.sqlx4k_tx_rollback

@Suppress("unused")
@OptIn(ExperimentalForeignApi::class)
class Transaction(private val id: Int) : Driver {

    suspend fun commit(): Result<Unit> = runCatching {
        sqlx { idx -> sqlx4k_tx_commit(idx, id, fn) }.throwIfError()
    }

    suspend fun rollback(): Result<Unit> = runCatching {
        sqlx { idx -> sqlx4k_tx_rollback(idx, id, fn) }.throwIfError()
    }

    override suspend fun query(sql: String): Result<Unit> = runCatching {
        sqlx { idx -> sqlx4k_tx_query(idx, id, sql, fn) }.throwIfError()
    }

    override suspend fun <T> fetchAll(sql: String, mapper: Sqlx4k.Row.() -> T): Result<List<T>> = runCatching {
        sqlx { idx -> sqlx4k_tx_fetch_all(idx, id, sql, fn) }.map { mapper(this) }
    }
}
