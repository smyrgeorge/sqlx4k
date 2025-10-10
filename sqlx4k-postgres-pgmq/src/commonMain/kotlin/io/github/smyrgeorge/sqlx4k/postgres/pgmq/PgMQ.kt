@file:Suppress("SqlNoDataSourceInspection")

package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.postgres.IPostgresSQL
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.extensions.toJsonString
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.BooleanRowMapper
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.BooleanRowMapper.toBooleanResult
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.LongRowMapper
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.LongRowMapper.toLongResult
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.MessageRowMapper
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.UnitRowMapper
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.UnitRowMapper.toUnitResult
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
        val sql = "SELECT pgmq.create(?)"
        val statement = Statement.create(sql).bind(0, queue)
        return tx.fetchAll(statement, UnitRowMapper).toUnitResult()
    }

    context(tx: Transaction)
    private suspend fun enableNotifyInsert(queue: String): Result<Unit> {
        // language=SQL
        val sql = "SELECT pgmq.enable_notify_insert(?)"
        val statement = Statement.create(sql).bind(0, queue)
        return tx.fetchAll(statement, UnitRowMapper).toUnitResult()
    }

    suspend fun drop(queue: Queue): Result<Boolean> = drop(queue.name)
    private suspend fun drop(queue: String): Result<Boolean> {
        // language=SQL
        val sql = "SELECT pgmq.drop_queue(?)"
        val statement = Statement.create(sql).bind(0, queue)
        return pg.fetchAll(statement, BooleanRowMapper).toBooleanResult()
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
        return db.fetchAll(statement, LongRowMapper).toLongResult() // returns the message-id.
    }

    suspend fun pop(queue: String, quantity: Int = 1): Result<List<Message>> {
        // language=SQL
        val sql = "SELECT pgmq.pop(queue_name := ?, quantity := ?)"
        val statement = Statement.create(sql).bind(0, queue).bind(1, quantity)
        return pg.fetchAll(statement, MessageRowMapper)
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
