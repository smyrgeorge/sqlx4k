package io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.postgres.IPostgresSQL
import io.github.smyrgeorge.sqlx4k.postgres.Notification
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.PgmqDbAdapter

class PgmqDbAdapterImpl(private val pg: IPostgresSQL) : PgmqDbAdapter {
    override val encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY
    override suspend fun listen(channel: String, f: suspend (Notification) -> Unit) = pg.listen(channel, f)
    override suspend fun begin(): Result<Transaction> = pg.begin()
    override suspend fun execute(sql: String): Result<Long> = pg.execute(sql)
    override suspend fun fetchAll(sql: String): Result<ResultSet> = pg.fetchAll(sql)
}
