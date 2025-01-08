package io.github.smyrgeorge.sqlx4k.bench.postgres.sqlx4k

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt

object Sqlx4kRowMapper : RowMapper<Sqlx4k> {
    override fun map(row: ResultSet.Row): Sqlx4k {
        val id: ResultSet.Row.Column = row.get(0)
        val test: ResultSet.Row.Column = row.get(1)
        return Sqlx4k(id = id.asInt(), test = test.asString())
    }
}
