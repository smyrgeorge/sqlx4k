package io.github.smyrgeorge.sqlx4k.examples.postgres

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface Sqlx4kRepository {
    @Query("SELECT * FROM sqlx4k WHERE id = :id")
    suspend fun selectById(id: Int): Statement

    @Query("SELECT * FROM sqlx4k")
    suspend fun selectAll(): Statement
}