package io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asIntOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.Metrics

internal object MetricsRowMapper : RowMapper<Metrics> {
    override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): Metrics {
        return Metrics(
            queueName = row.get("queue_name").asString(),
            queueLength = row.get("queue_length").asLong(),
            newestMsgAgeSec = row.get("newest_msg_age_sec").asIntOrNull() ?: 0,
            oldestMsgAgeSec = row.get("oldest_msg_age_sec").asIntOrNull() ?: 0,
            totalMessages = row.get("total_messages").asLong(),
            scrapeTime = row.get("scrape_time").asInstant(),
            queueVisibleLength = row.get("queue_visible_length").asLong(),
        )
    }
}
