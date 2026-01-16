package io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.QueueRecord

internal object QueueRecordRowMapper : RowMapper<QueueRecord> {
    override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): QueueRecord {
        return QueueRecord(
            name = row.get("queue_name").asString(),
            partitioned = row.get("is_partitioned").asBoolean(),
            unlogged = row.get("is_unlogged").asBoolean(),
            createdAt = row.get("created_at").asInstant(),
        )
    }
}
