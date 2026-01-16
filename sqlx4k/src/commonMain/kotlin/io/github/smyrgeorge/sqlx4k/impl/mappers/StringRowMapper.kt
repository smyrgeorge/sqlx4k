package io.github.smyrgeorge.sqlx4k.impl.mappers

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry

internal object StringRowMapper : RowMapper<String> {
    override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): String {
        require(row.size == 1) { "Expected a single column, got ${row.size}" }
        return row.get(0).asString()
    }

    fun Result<List<String>>.toSingleString(): Result<String> = runCatching {
        return map { rs ->
            check(rs.size == 1) { "Expected a single row, got ${rs.size}" }
            rs.first()
        }
    }
}
