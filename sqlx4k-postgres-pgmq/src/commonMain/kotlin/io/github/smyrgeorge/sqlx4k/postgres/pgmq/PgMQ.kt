@file:Suppress("SqlNoDataSourceInspection")

package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.postgres.IPostgresSQL
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.extensions.toJsonString
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.BooleanRowMapper
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.BooleanRowMapper.toSingleBooleanResult
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.LongRowMapper
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.LongRowMapper.toSingleLongResult
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.MessageRowMapper
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.UnitRowMapper
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.UnitRowMapper.toSingleUnitResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PgMQ(
    private val pg: IPostgresSQL,
    val options: Options = Options()
) {
    suspend fun create(queue: Queue): Result<Unit> {
        return pg.transaction {
            create(queue.name).getOrThrow()
            if (queue.enableNotifyInsert) enableNotifyInsert(queue.name).getOrThrow()
            Result.success(Unit)
        }
    }

    context(tx: Transaction)
    private suspend fun create(queue: String): Result<Unit> {
        // language=SQL
        val sql = "SELECT pgmq.create(queue_name := ?)"
        val statement = Statement.create(sql).bind(0, queue)
        return tx.fetchAll(statement, UnitRowMapper).toSingleUnitResult()
    }

    context(tx: Transaction)
    private suspend fun enableNotifyInsert(queue: String): Result<Unit> {
        // language=SQL
        val sql = "SELECT pgmq.enable_notify_insert(queue_name := ?)"
        val statement = Statement.create(sql).bind(0, queue)
        return tx.fetchAll(statement, UnitRowMapper).toSingleUnitResult()
    }

    suspend fun drop(queue: Queue): Result<Boolean> = drop(queue.name)
    private suspend fun drop(queue: String): Result<Boolean> {
        // language=SQL
        val sql = "SELECT pgmq.drop_queue(queue_name := ?)"
        val statement = Statement.create(sql).bind(0, queue)
        return pg.fetchAll(statement, BooleanRowMapper).toSingleBooleanResult()
    }

    suspend fun send(
        queue: String,
        message: Map<String, String?>,
        headers: Map<String, String>,
        delay: Duration = 0.seconds
    ): Result<Long> = send(queue, message.toJsonString(), headers, delay)

    suspend fun send(
        queue: String,
        message: String,
        headers: Map<String, String>,
        delay: Duration = 0.seconds
    ): Result<Long> = with(pg) { send(queue, message, headers, delay) }

    context(db: QueryExecutor)
    suspend fun send(
        queue: String,
        message: Map<String, String?>,
        headers: Map<String, String>,
        delay: Duration = 0.seconds
    ): Result<Long> = send(queue, message.toJsonString(), headers, delay)

    context(db: QueryExecutor)
    suspend fun send(
        queue: String,
        message: String,
        headers: Map<String, String>,
        delay: Duration = 0.seconds
    ): Result<Long> {
        // language=SQL
        val sql = "SELECT pgmq.send(queue_name := ?, msg := ?, headers := ?, delay := ?)"
        val statement = Statement.create(sql)
            .bind(0, queue)
            .bind(1, message)
            .bind(2, headers.toJsonString())
            .bind(3, delay.inWholeSeconds)
        return db.fetchAll(statement, LongRowMapper).toSingleLongResult() // returns the message-id.
    }

    suspend fun pop(queue: String, quantity: Int = 1): Result<List<Message>> = with(pg) { pop(queue, quantity) }

    context(db: QueryExecutor)
    suspend fun pop(queue: String, quantity: Int = 1): Result<List<Message>> {
        // language=SQL
        val sql = "SELECT * FROM pgmq.pop(queue_name := ?, qty := ?)"
        val statement = Statement.create(sql).bind(0, queue).bind(1, quantity)
        return db.fetchAll(statement, MessageRowMapper)
    }

    suspend fun read(queue: String, quantity: Int = 1, vt: Duration = 30.seconds): Result<List<Message>> =
        with(pg) { read(queue, quantity, vt) }

    context(db: QueryExecutor)
    suspend fun read(queue: String, quantity: Int = 1, vt: Duration = 30.seconds): Result<List<Message>> {
        // language=SQL
        val sql = "SELECT * FROM pgmq.read(queue_name := ?, qty := ?, vt := ?)"
        val statement = Statement.create(sql)
            .bind(0, queue)
            .bind(1, quantity)
            .bind(2, vt.inWholeSeconds)
        return db.fetchAll(statement, MessageRowMapper)
    }

    suspend fun archive(queue: String, id: Long): Result<List<Long>> = with(pg) { archive(queue, listOf(id)) }
    suspend fun archive(queue: String, ids: List<Long>): Result<List<Long>> = with(pg) { archive(queue, ids) }

    context(db: QueryExecutor)
    suspend fun archive(queue: String, id: Long): Result<List<Long>> = archive(queue, listOf(id))

    context(db: QueryExecutor)
    suspend fun archive(queue: String, ids: List<Long>): Result<List<Long>> {
        // language=SQL
        val sql = "SELECT pgmq.archive(queue_name := ?, msg_ids := ?)"
        val statement = Statement.create(sql).bind(0, queue).bind(1, ids)
        return db.fetchAll(statement, LongRowMapper).mapCatching {
            val archived = it.all { id -> id in ids }
            require(archived) { "Some of the given ids could not be archived." }
            it
        }
    }

    suspend fun delete(queue: String, id: Long): Result<List<Long>> = with(pg) { delete(queue, listOf(id)) }
    suspend fun delete(queue: String, ids: List<Long>): Result<List<Long>> = with(pg) { delete(queue, ids) }

    context(db: QueryExecutor)
    suspend fun delete(queue: String, id: Long): Result<List<Long>> = delete(queue, listOf(id))

    context(db: QueryExecutor)
    suspend fun delete(queue: String, ids: List<Long>): Result<List<Long>> {
        // language=SQL
        val sql = "SELECT pgmq.delete(queue_name := ?, msg_ids := ?)"
        val statement = Statement.create(sql).bind(0, queue).bind(1, ids)
        return db.fetchAll(statement, LongRowMapper).mapCatching {
            val deleted = it.all { id -> id in ids }
            require(deleted) { "Some of the given ids could not be deleted." }
            it
        }
    }

    data class Queue(
        val name: String,
        val enableNotifyInsert: Boolean = false,
        val queueMinPullDelay: Duration? = null,
        val queueMaxPullDelay: Duration? = null,
    )

    data class Options(
        val queueMinPullDelay: Duration = defaultQueueMinPullDelay,
        val queueMaxPullDelay: Duration = defaultQueueMaxPullDelay,
    )

    companion object {
        val defaultQueueMinPullDelay: Duration = 50.milliseconds
        val defaultQueueMaxPullDelay: Duration = 1000.milliseconds
    }
}
