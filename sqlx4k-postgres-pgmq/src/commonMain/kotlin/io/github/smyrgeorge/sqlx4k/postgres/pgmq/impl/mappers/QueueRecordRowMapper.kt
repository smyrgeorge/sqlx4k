@file:OptIn(ExperimentalTime::class)

package io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.QueueRecord
import kotlin.time.ExperimentalTime

internal object QueueRecordRowMapper : RowMapper<QueueRecord> {
    override fun map(row: ResultSet.Row): QueueRecord {
        return QueueRecord(
            name = row.get(0).asString(),
            partitioned = row.get(1).asBoolean(),
            unlogged = row.get(2).asBoolean(),
            createdAt = row.get(3).asInstant(),
        )
    }
}