package io.github.smyrgeorge.sqlx4k.examples.postgres

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt

object Sqlx4kRowMapper : RowMapper<Sqlx4k> {
    override fun map(rs: ResultSet, row: ResultSet.Row): Sqlx4k {
        val id: ResultSet.Row.Column = row.get("id")
        val test: ResultSet.Row.Column = row.get("test")
        return Sqlx4k(id = id.asInt(), test = test.asString())
    }
}
