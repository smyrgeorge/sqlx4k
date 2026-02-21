package io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.Message
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.extensions.toStringMap

internal object MessageRowMapper : RowMapper<Message> {
    // Column order (pgmq >= 1.5): msg_id(0), read_ct(1), enqueued_at(2), last_read_at(3), vt(4), message(5), headers(6)
    override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): Message {
        return Message(
            msgId = row.get(0).asLong(),
            readCt = row.get(1).asInt(),
            enqueuedAt = row.get(2).asInstant(),
            lastReadAt = row.get(3).asInstantOrNull(),
            vt = row.get(4).asInstant(),
            message = row.get(5).asString(),
            headers = row.get(6).asStringOrNull()?.toStringMap() ?: emptyMap(),
        )
    }
}
