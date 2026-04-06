@file:Suppress("SqlResolve", "SqlNoDataSourceInspection")

package io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.extensions

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.types.NoWrappingTuple
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.Message
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.BooleanRowMapper
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.BooleanRowMapper.toSingleBoolean
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.LongRowMapper
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.LongRowMapper.toSingleLong
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.MessageRowMapper
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.UnitRowMapper
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.UnitRowMapper.toSingleUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

context(db: QueryExecutor)
suspend fun send(
    queue: String,
    message: String,
    headers: Map<String, String> = emptyMap(),
    delay: Duration = 0.seconds
): Result<Long> {
    // language=PostgreSQL
    val sql = "SELECT pgmq.send(queue_name := ?, msg := ?, headers := ?, delay := ?)"
    val statement = Statement.create(sql)
        .bind(0, queue)
        .bind(1, message)
        .bind(2, headers.toJsonString())
        .bind(3, delay.inWholeSeconds.toInt())
    return db.fetchAll(statement, LongRowMapper).toSingleLong() // returns the message-id.
}

context(db: QueryExecutor)
suspend fun send(
    queue: String,
    messages: List<String>,
    headers: Map<String, String> = emptyMap(),
    delay: Duration = 0.seconds
): Result<List<Long>> {
    // language=PostgreSQL
    // headers must be jsonb[] with one entry per message — replicate the shared headers map for each
    val sql =
        "SELECT pgmq.send_batch(queue_name := ?, msgs := ARRAY[?]::jsonb[], headers := ARRAY[?]::jsonb[], delay := ?)"
    val statement = Statement.create(sql)
        .bind(0, queue)
        .bind(1, NoWrappingTuple(messages))
        .bind(2, NoWrappingTuple(messages.map { headers.toJsonString() }))
        .bind(3, delay.inWholeSeconds.toInt())
    return db.fetchAll(statement, LongRowMapper) // returns the message-ids.
}

context(db: QueryExecutor)
suspend fun pop(queue: String, quantity: Int = 1): Result<List<Message>> {
    // language=PostgreSQL
    val sql = "SELECT * FROM pgmq.pop(queue_name := ?, qty := ?)"
    val statement = Statement.create(sql).bind(0, queue).bind(1, quantity)
    return db.fetchAll(statement, MessageRowMapper)
}

context(db: QueryExecutor)
suspend fun read(queue: String, quantity: Int = 1, vt: Duration = 30.seconds): Result<List<Message>> {
    // language=PostgreSQL
    val sql = "SELECT * FROM pgmq.read(queue_name := ?, qty := ?, vt := ?)"
    val statement = Statement.create(sql)
        .bind(0, queue)
        .bind(1, quantity)
        .bind(2, vt.inWholeSeconds.toInt())
    return db.fetchAll(statement, MessageRowMapper)
}

context(db: QueryExecutor)
suspend fun archive(queue: String, id: Long): Result<Boolean> {
    // language=PostgreSQL
    val sql = "SELECT pgmq.archive(queue_name := ?, msg_id := ?)"
    val statement = Statement.create(sql).bind(0, queue).bind(1, id)
    return db.fetchAll(statement, BooleanRowMapper).toSingleBoolean()
}

context(db: QueryExecutor)
suspend fun archive(queue: String, ids: List<Long>): Result<List<Long>> {
    // language=PostgreSQL
    val sql = "SELECT pgmq.archive(queue_name := ?, msg_ids := ARRAY[?])"
    val statement = Statement.create(sql).bind(0, queue).bind(1, NoWrappingTuple(ids))
    return db.fetchAll(statement, LongRowMapper).mapCatching {
        val archived = it.all { id -> id in ids }
        check(archived) { "Some of the given ids could not be archived." }
        it
    }
}

context(db: QueryExecutor)
suspend fun delete(queue: String, id: Long): Result<Boolean> {
    // language=PostgreSQL
    val sql = "SELECT pgmq.delete(queue_name := ?, msg_id := ?)"
    val statement = Statement.create(sql).bind(0, queue).bind(1, id)
    return db.fetchAll(statement, BooleanRowMapper).toSingleBoolean()
}

context(db: QueryExecutor)
suspend fun delete(queue: String, ids: List<Long>): Result<List<Long>> {
    // language=PostgreSQL
    val sql = "SELECT pgmq.delete(queue_name := ?, msg_ids := ARRAY[?])"
    val statement = Statement.create(sql).bind(0, queue).bind(1, NoWrappingTuple(ids))
    return db.fetchAll(statement, LongRowMapper).mapCatching {
        val deleted = it.all { id -> id in ids }
        check(deleted) { "Some of the given ids could not be deleted." }
        it
    }
}

context(db: QueryExecutor)
suspend fun setVt(queue: String, id: Long, vt: Duration): Result<Long> {
    // language=PostgreSQL
    val sql = "SELECT msg_id FROM pgmq.set_vt(queue_name := ?, msg_id := ?, vt := ?)"
    val statement = Statement.create(sql).bind(0, queue).bind(1, id).bind(2, vt.inWholeSeconds.toInt())
    return db.fetchAll(statement, LongRowMapper).toSingleLong()
}

context(db: QueryExecutor)
suspend fun bindTopic(pattern: String, queueName: String): Result<Unit> {
    // language=PostgreSQL
    val sql = "SELECT pgmq.bind_topic(pattern := ?, queue_name := ?)"
    val statement = Statement.create(sql).bind(0, pattern).bind(1, queueName)
    return db.fetchAll(statement, UnitRowMapper).toSingleUnit()
}

context(db: QueryExecutor)
suspend fun sendTopic(
    routingKey: String,
    message: String,
    headers: Map<String, String> = emptyMap(),
    delay: Duration = 0.seconds
): Result<Long> {
    // language=PostgreSQL
    val sql = "SELECT pgmq.send_topic(routing_key := ?, msg := ?, headers := ?, delay := ?)"
    val statement = Statement.create(sql)
        .bind(0, routingKey)
        .bind(1, message)
        .bind(2, headers.toJsonString())
        .bind(3, delay.inWholeSeconds)
    return db.fetchAll(statement, LongRowMapper).toSingleLong()
}
