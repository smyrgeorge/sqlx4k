package io.github.smyrgeorge.sqlx4k.driver

import io.github.smyrgeorge.sqlx4k.Sqlx4k
import kotlinx.cinterop.ExperimentalForeignApi
import librust_lib.sqlx4k_tx_commit
import librust_lib.sqlx4k_tx_fetch_all
import librust_lib.sqlx4k_tx_query
import librust_lib.sqlx4k_tx_rollback

@Suppress("unused")
@OptIn(ExperimentalForeignApi::class)
class Transaction(private val id: Int) : Driver {
    suspend fun commit(): Unit = io { sqlx4k_tx_commit(id) }
    suspend fun rollback(): Unit = io { sqlx4k_tx_rollback(id) }
    override suspend fun query(sql: String): Result<Unit> = io {
        runCatching { sqlx4k_tx_query(id, sql).orThrow() }
    }

    override suspend fun <T> fetchAll(sql: String, mapper: Sqlx4k.Row.() -> T): Result<List<T>> = io {
        runCatching { sqlx4k_tx_fetch_all(id, sql).map { mapper(this) } }
    }
}
