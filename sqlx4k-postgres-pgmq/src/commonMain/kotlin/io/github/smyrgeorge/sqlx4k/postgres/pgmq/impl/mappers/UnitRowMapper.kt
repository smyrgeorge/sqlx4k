package io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper

internal object UnitRowMapper : RowMapper<Unit> {
    override fun map(row: ResultSet.Row) {
        require(row.size == 1) { "Expected a single column, got ${row.size}" }
    }

    fun Result<List<Unit>>.toSingleUnitResult(): Result<Unit> = runCatching {
        return map { rs ->
            check(rs.size == 1) { "Expected a single row, got ${rs.size}" }
            rs.first()
        }
    }
}