@file:Suppress("SqlNoDataSourceInspection")

package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.types.NoWrappingTuple
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.extensions.toJsonString
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.*
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.BooleanRowMapper.toSingleBooleanResult
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.LongRowMapper.toSingleLongResult
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.UnitRowMapper.toSingleUnitResult
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PgMqClient(
    val pg: PgMqDbAdapter,
    private val options: Options = Options()
) {
    init {
        // Ensure the pgmq extensions is installed.
        if (options.verifyInstallation) runBlocking { install() }
    }

    private suspend fun install() {
        suspend fun installed(): Boolean {
            // language=SQL
            val sql = "SELECT pgmq._extension_exists('pgmq')"
            return pg.fetchAll(Statement.create(sql), BooleanRowMapper).toSingleBooleanResult().getOrThrow()
        }

        if (!installed()) {
            if (!options.autoInstall) error("Could not verify the 'pgmq' installation.")
            // language=SQL
            val sql = "CREATE EXTENSION IF NOT EXISTS pgmq"
            pg.execute(sql).getOrElse { error("Could not create the 'pgmq' extension (${it.message}).") }
        }
        // Recheck.
        if (!installed()) error("Could not verify the 'pgmq' installation.")
    }

    suspend fun create(queue: Queue): Result<Unit> = runCatching {
        context(tx: Transaction)
        suspend fun create(queue: String, unlogged: Boolean): Result<Unit> {
            // language=SQL
            val sql = if (unlogged) "SELECT pgmq.create_unlogged(queue_name := ?)"
            else "SELECT pgmq.create(queue_name := ?)"
            val statement = Statement.create(sql).bind(0, queue)
            return tx.fetchAll(statement, UnitRowMapper).toSingleUnitResult()
        }

        context(tx: Transaction)
        suspend fun enableNotifyInsert(queue: String): Result<Unit> {
            // language=SQL
            val sql = "SELECT pgmq.enable_notify_insert(queue_name := ?)"
            val statement = Statement.create(sql).bind(0, queue)
            return tx.fetchAll(statement, UnitRowMapper).toSingleUnitResult()
        }

        return pg.transaction {
            val existing = listQueues().getOrThrow().find { it.name == queue.name }
            existing?.let {
                check(it.unlogged == queue.unlogged) { "Queue '${queue.name}' already exists with a different unlogged flag." }
                check(!it.partitioned) { "Queue '${queue.name}' already exists with a partitioned flag (partitioning is not yet supported by this client)." }
                return@transaction Result.success(Unit)
            }

            create(queue.name, queue.unlogged).getOrThrow()
            if (queue.enableNotifyInsert) enableNotifyInsert(queue.name).getOrThrow()
            Result.success(Unit)
        }
    }

    suspend fun listQueues(): Result<List<QueueRecord>> {
        // language=SQL
        val sql = "SELECT * FROM pgmq.list_queues()"
        return pg.fetchAll(sql, QueueRecordRowMapper)
    }

    suspend fun drop(queue: Queue): Result<Boolean> = drop(queue.name)
    private suspend fun drop(queue: String): Result<Boolean> {
        // language=SQL
        val sql = "SELECT pgmq.drop_queue(queue_name := ?)"
        val statement = Statement.create(sql).bind(0, queue)
        return pg.fetchAll(statement, BooleanRowMapper).toSingleBooleanResult()
    }

    suspend fun purge(queue: Queue): Result<Long> = purge(queue.name)
    private suspend fun purge(queue: String): Result<Long> {
        // language=SQL
        val sql = "SELECT pgmq.purge_queue(queue_name := ?)"
        val statement = Statement.create(sql).bind(0, queue)
        return pg.fetchAll(statement, LongRowMapper).toSingleLongResult() // returns the number of messages purged.
    }

    suspend fun send(
        queue: String,
        message: Map<String, String?>,
        headers: Map<String, String> = emptyMap(),
        delay: Duration = 0.seconds
    ): Result<Long> = send(queue, message.toJsonString(), headers, delay)

    suspend fun send(
        queue: String,
        message: String,
        headers: Map<String, String> = emptyMap(),
        delay: Duration = 0.seconds
    ): Result<Long> = with(pg) { send(queue, message, headers, delay) }

    context(db: QueryExecutor)
    suspend fun send(
        queue: String,
        message: Map<String, String?>,
        headers: Map<String, String> = emptyMap(),
        delay: Duration = 0.seconds
    ): Result<Long> = send(queue, message.toJsonString(), headers, delay)

    context(db: QueryExecutor)
    suspend fun send(
        queue: String,
        message: String,
        headers: Map<String, String> = emptyMap(),
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

    suspend fun archive(queue: String, id: Long): Result<Boolean> = with(pg) { archive(queue, id) }
    suspend fun archive(queue: String, ids: List<Long>): Result<List<Long>> = with(pg) { archive(queue, ids) }

    context(db: QueryExecutor)
    suspend fun archive(queue: String, id: Long): Result<Boolean> {
        // language=SQL
        val sql = "SELECT pgmq.archive(queue_name := ?, msg_id := ?)"
        val statement = Statement.create(sql).bind(0, queue).bind(1, id)
        return db.fetchAll(statement, BooleanRowMapper).toSingleBooleanResult()
    }

    context(db: QueryExecutor)
    suspend fun archive(queue: String, ids: List<Long>): Result<List<Long>> {
        // language=SQL
        val sql = "SELECT pgmq.archive(queue_name := ?, msg_ids := ARRAY[?])"
        val statement = Statement.create(sql).bind(0, queue).bind(1, NoWrappingTuple(ids))
        return db.fetchAll(statement, LongRowMapper).mapCatching {
            val archived = it.all { id -> id in ids }
            check(archived) { "Some of the given ids could not be archived." }
            it
        }
    }

    suspend fun delete(queue: String, id: Long): Result<Boolean> = with(pg) { delete(queue, id) }
    suspend fun delete(queue: String, ids: List<Long>): Result<List<Long>> = with(pg) { delete(queue, ids) }

    context(db: QueryExecutor)
    suspend fun delete(queue: String, id: Long): Result<Boolean> {
        // language=SQL
        val sql = "SELECT pgmq.delete(queue_name := ?, msg_id := ?)"
        val statement = Statement.create(sql).bind(0, queue).bind(1, id)
        return db.fetchAll(statement, BooleanRowMapper).toSingleBooleanResult()
    }

    context(db: QueryExecutor)
    suspend fun delete(queue: String, ids: List<Long>): Result<List<Long>> {
        // language=SQL
        val sql = "SELECT pgmq.delete(queue_name := ?, msg_ids := ARRAY[?])"
        val statement = Statement.create(sql).bind(0, queue).bind(1, NoWrappingTuple(ids))
        return db.fetchAll(statement, LongRowMapper).mapCatching {
            val deleted = it.all { id -> id in ids }
            check(deleted) { "Some of the given ids could not be deleted." }
            it
        }
    }

    suspend fun setVt(queue: String, id: Long, vt: Duration): Result<Long> = with(pg) { setVt(queue, id, vt) }

    context(db: QueryExecutor)
    suspend fun setVt(queue: String, id: Long, vt: Duration): Result<Long> {
        // language=SQL
        val sql = "SELECT msg_id FROM pgmq.set_vt(queue_name := ?, msg_id := ?, vt := ?)"
        val statement = Statement.create(sql).bind(0, queue).bind(1, id).bind(2, vt.inWholeSeconds)
        return db.fetchAll(statement, LongRowMapper).toSingleLongResult()
    }

    suspend fun metrics(queue: String): Result<Metrics> {
        // language=SQL
        val sql = "SELECT * FROM pgmq.metrics(queue_name := ?)"
        val statement = Statement.create(sql).bind(0, queue)
        return pg.fetchAll(statement, MetricsRowMapper).mapCatching {
            check(it.isNotEmpty()) { "No metrics found for queue '$queue'." }
            check(it.size == 1) { "Multiple metrics found for queue '$queue'." }
            it.first()
        }
    }

    suspend fun metrics(): Result<List<Metrics>> {
        // language=SQL
        val sql = "SELECT * FROM pgmq.metrics()"
        return pg.fetchAll(sql, MetricsRowMapper)
    }

    suspend fun ack(queue: String, id: Long): Result<Boolean> = delete(queue, id)
    suspend fun ack(queue: String, ids: List<Long>): Result<List<Long>> = delete(queue, ids)
    suspend fun nack(queue: String, id: Long, vt: Duration = Duration.ZERO): Result<Long> = setVt(queue, id, vt)

    data class Queue(
        val name: String,
        val unlogged: Boolean = false,
        val enableNotifyInsert: Boolean = false,
    ) {
        init {
            require(name.isNotEmpty()) { "Queue name must not be empty" }
            require(name.isNotBlank()) { "Queue name must not be blank" }
        }
    }

    data class Options(
        val autoInstall: Boolean = true,
        val verifyInstallation: Boolean = true,
    )
}
