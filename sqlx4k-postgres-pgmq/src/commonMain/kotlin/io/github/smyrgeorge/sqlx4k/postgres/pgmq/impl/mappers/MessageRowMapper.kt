package io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.Message
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.extensions.toStringMap

internal object MessageRowMapper : RowMapper<Message> {
    override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): Message {
        return Message(
            msgId = row.get(0).asLong(),
            readCt = row.get(1).asInt(),
            enqueuedAt = row.get(2).asInstant(),
            vt = row.get(3).asInstant(),
            message = row.get(4).asString(),
            headers = row.get(5).asStringOrNull()?.toStringMap() ?: emptyMap(),
        )
    }
}
