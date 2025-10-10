package io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong

internal object LongRowMapper : RowMapper<Long> {
    override fun map(row: ResultSet.Row): Long {
        require(row.size == 1) { "Expected a single column, got ${row.size}" }
        return row.get(0).asLong()
    }

    fun Result<List<Long>>.toLongResult(): Result<Long> = runCatching {
        return map { rs ->
            require(rs.size == 1) { "Expected a single row, got ${rs.size}" }
            rs.first()
        }
    }
}