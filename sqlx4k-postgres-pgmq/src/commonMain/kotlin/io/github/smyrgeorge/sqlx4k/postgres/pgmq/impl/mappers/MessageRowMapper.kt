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
    override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): Message {
        return Message(
            msgId = row.get("msg_id").asLong(),
            readCt = row.get("read_ct").asInt(),
            enqueuedAt = row.get("enqueued_at").asInstant(),
            lastReadAt = row.get("last_read_at").asInstantOrNull(),
            vt = row.get("vt").asInstant(),
            message = row.get("message").asString(),
            headers = row.get("headers").asStringOrNull()?.toStringMap() ?: emptyMap(),
        )
    }
}
