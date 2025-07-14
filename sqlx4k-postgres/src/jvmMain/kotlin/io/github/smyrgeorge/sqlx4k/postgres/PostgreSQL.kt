package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.*
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlin.jvm.optionals.getOrElse
import kotlin.time.toJavaDuration

class PostgreSQL(
    url: String,
    host: String,
    port: Int,
    database: String,
    username: String,
    password: String,
    options: Driver.Pool.Options = Driver.Pool.Options(),
) : Driver, Driver.Pool, Driver.Transactional, Driver.Migrate {

    private val pool: ConnectionPool = createConnectionPool(
        host = host,
        port = port,
        database = database,
        username = username,
        password = password,
        options = options
    ).apply {
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch { runCatching { warmup().awaitSingle() } }
    }

    override suspend fun migrate(path: String): Result<Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun close(): Result<Unit> = runCatching {
        pool.disposeLater().awaitSingle()
    }

    override fun poolSize(): Int = pool.metrics.getOrElse { error("No metrics available.") }.maxAllocatedSize
    override fun poolIdleSize(): Int = pool.metrics.getOrElse { error("No metrics available.") }.idleSize()

    override suspend fun execute(sql: String): Result<Long> = runCatching {
        @Suppress("SqlSourceToSinkFlow")
        pool.create().awaitSingle().createStatement(sql).execute().awaitSingle().rowsUpdated.awaitSingle()
    }

    override suspend fun execute(statement: Statement): Result<Long> =
        execute(statement.render(encoders))

    override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
        fun  io.r2dbc.spi.Row.toRow(): ResultSet.Row {
            val columns = metadata.columnMetadatas.mapIndexed { i, c ->
                ResultSet.Row.Column(
                    ordinal = i,
                    name = c.name,
                    type = c.type.name,
                    value = get(i, String::class.java)
                )
            }
            return ResultSet.Row(columns)
        }

        suspend fun io.r2dbc.spi.Result.toResultSet(): ResultSet {
            val rows = map { r, _ -> r.toRow() }.asFlow().toList()
            val meta = if (rows.isEmpty()) ResultSet.Metadata(emptyList())
            else rows.first().toMetadata()
            // TODO: handle error?
            return ResultSet(rows, null, meta)
        }

        @Suppress("SqlSourceToSinkFlow")
        pool.create().awaitSingle().createStatement(sql).execute().awaitSingle().toResultSet()
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        fetchAll(statement.render(encoders))

    override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
        fetchAll(statement.render(encoders), rowMapper)

    override suspend fun begin(): Result<Transaction> {
        TODO("Not yet implemented")
    }

    companion object {
        /**
         * The `ValueEncoderRegistry` instance used for encoding values supplied to SQL statements in the `PostgreSQL` class.
         * This registry maps data types to their corresponding encoders, which convert values into a format suitable for
         * inclusion in SQL queries.
         *
         * This registry is utilized in methods like `execute`, `fetchAll`, and other database operation methods to ensure
         * that parameters bound to SQL statements are correctly encoded before being executed.
         */
        val encoders = Statement.ValueEncoderRegistry()

        private fun createConnectionPool(
            host: String,
            port: Int,
            database: String,
            username: String,
            password: String,
            options: Driver.Pool.Options
        ): ConnectionPool {
            val connectionFactory = PostgresqlConnectionFactory(
                PostgresqlConnectionConfiguration.builder()
                    .host(host)
                    .port(port)
                    .database(database)
                    .username(username)
                    .password(password)
                    .build()
            )

            val config = ConnectionPoolConfiguration.builder(connectionFactory).apply {
                options.minConnections?.let { minIdle(it) }
                maxSize(options.maxConnections)
                options.acquireTimeout?.let { maxAcquireTime(it.toJavaDuration()) }
                options.idleTimeout?.let { maxIdleTime(it.toJavaDuration()) }
                options.maxLifetime?.let { maxLifeTime(it.toJavaDuration()) }
            }

            return ConnectionPool(config.build())
        }
    }
}
