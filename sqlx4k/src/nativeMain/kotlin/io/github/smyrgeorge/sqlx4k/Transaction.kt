package io.github.smyrgeorge.sqlx4k

@Suppress("unused")
interface Transaction : Driver {
    val id: Int
    suspend fun commit(): Result<Unit>
    suspend fun rollback(): Result<Unit>
    override suspend fun query(sql: String): Result<Unit>
    override suspend fun <T> fetchAll(sql: String, mapper: Sqlx4k.Row.() -> T): Result<List<T>>
}
