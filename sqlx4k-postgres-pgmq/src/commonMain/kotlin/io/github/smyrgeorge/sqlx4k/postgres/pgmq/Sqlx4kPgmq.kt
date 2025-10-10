@file:Suppress("SqlNoDataSourceInspection")

package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.postgres.IPostgresSQL
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.BooleanRowMapper
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.BooleanRowMapper.toBooleanResult
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.UnitRowMapper
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.UnitRowMapper.toUnitResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class Sqlx4kPgmq(
    private val db: IPostgresSQL,
    val options: Options = Options()
) {

    suspend fun create(queue: Queue): Result<Unit> {
        return db.transaction {
            create(queue.name).getOrThrow()
            if (queue.conf.enableNotifyInsert) enableNotifyInsert(queue.name).getOrThrow()
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
        return db.fetchAll(statement, BooleanRowMapper).toBooleanResult()
    }

    data class Queue(
        val name: String,
        val conf: Conf = Conf()
    ) {
        data class Conf(val enableNotifyInsert: Boolean = false)
    }

    data class Options(
        val queueMinPullDelay: Duration = 50.milliseconds,
        val queueMaxPullDelay: Duration = 1000.milliseconds,
    )
}