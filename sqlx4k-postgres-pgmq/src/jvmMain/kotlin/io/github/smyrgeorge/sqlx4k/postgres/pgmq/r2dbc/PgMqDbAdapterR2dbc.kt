package io.github.smyrgeorge.sqlx4k.postgres.pgmq.r2dbc

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.postgres.Notification
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQLImpl
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.PgMqDbAdapter
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.postgresql.PostgresqlConnectionFactory

/**
 * Implementation of the `PgMqDbAdapter` interface using R2DBC for asynchronous
 * interaction with a PostgreSQL database.
 *
 * This class provides an abstraction layer to handle database operations such as
 * executing SQL statements, fetching results, managing transactions, and listening to
 * PostgreSQL notifications on specified channels.
 *
 * @param pool The connection pool used for acquiring database connections.
 * @param connectionFactory The factory for creating individual PostgreSQL connections.
 */
class PgMqDbAdapterR2dbc(
    private val connectionFactory: PostgresqlConnectionFactory,
    pool: ConnectionPool
) : PgMqDbAdapter {
    override val encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY
    private val adapter = PostgreSQLImpl(pool, connectionFactory, encoders)
    override suspend fun listen(channel: String, f: suspend (Notification) -> Unit) = adapter.listen(channel, f)
    override suspend fun begin(): Result<Transaction> = adapter.begin()
    override suspend fun execute(sql: String): Result<Long> = adapter.execute(sql)
    override suspend fun fetchAll(sql: String): Result<ResultSet> = adapter.fetchAll(sql)
}
