package io.github.smyrgeorge.sqlx4k.bench.postgres.sqlx4k

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.random.Random

@Table("sqlx4k")
data class Sqlx4k(
    @Id(insert = true)
    val id: Int,
    val test: String,
) {
    companion object {
        fun random() = Sqlx4k(id = Random.nextInt(), test = "test")
    }
}
