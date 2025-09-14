package io.github.smyrgeorge.sqlx4k.examples.mysql

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table

@Table("sqlx4k")
data class Sqlx4k(
    @Id(insert = true) // Will be included in the insert query.
    val id: Int,
    val test: String,
)
