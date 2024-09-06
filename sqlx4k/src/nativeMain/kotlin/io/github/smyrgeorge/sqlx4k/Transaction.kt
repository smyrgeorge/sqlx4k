package io.github.smyrgeorge.sqlx4k

import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

@Suppress("unused")
@OptIn(ExperimentalForeignApi::class)
interface Transaction : Driver {
    var tx: CPointer<out CPointed>
    suspend fun commit(): Result<Unit>
    suspend fun rollback(): Result<Unit>
    override suspend fun execute(sql: String): Result<ULong>
    override suspend fun <T> fetchAll(sql: String, mapper: ResultSet.Row.() -> T): Result<List<T>>
}
