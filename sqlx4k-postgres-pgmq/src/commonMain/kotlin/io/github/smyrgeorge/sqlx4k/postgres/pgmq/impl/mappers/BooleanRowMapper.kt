package io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean

internal object BooleanRowMapper : RowMapper<Boolean> {
    override fun map(row: ResultSet.Row): Boolean {
        require(row.size == 1) { "Expected a single column, got ${row.size}" }
        return row.get(0).asBoolean()
    }

    fun Result<List<Boolean>>.toSingleBoolean(): Result<Boolean> = runCatching {
        return map { rs ->
            check(rs.size == 1) { "Expected a single row, got ${rs.size}" }
            rs.first()
        }
    }
}
