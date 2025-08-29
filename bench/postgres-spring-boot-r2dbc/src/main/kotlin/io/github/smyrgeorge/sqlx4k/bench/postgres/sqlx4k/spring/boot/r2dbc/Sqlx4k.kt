package io.github.smyrgeorge.sqlx4k.bench.postgres.sqlx4k.spring.boot.r2dbc

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import kotlin.random.Random

@Table(name = "sqlx4k")
data class Sqlx4k(
    @Id
    val id: Int,
    val test: String,
) {
    companion object {
        fun random() = Sqlx4k(id = Random.nextInt(), test = "test")
    }
}
