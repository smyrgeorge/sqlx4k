package io.github.smyrgeorge.sqlx4k.bench.postgres.spring.boot.r2dbc

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table(name = "sqlx4k")
data class Sqlx4k(
    @Id
    val id: Int,
    val test: String,
)
