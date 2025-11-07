package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Statement
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import java.net.URI
import kotlin.time.toJavaDuration
import io.r2dbc.pool.ConnectionPool as R2dbcConnectionPool

/**
 * PostgreSQL class provides mechanisms to interact with a PostgreSQL database.
 * It implements `Driver`, `Driver.Pool`, and `Driver.Transactional` interfaces,
 * offering functionalities such as connection pooling, executing queries,
 * fetching data, and handling transactions.
 *
 *  The URL scheme designator can be either `postgresql://` or `postgres://`.
 *  Each of the URL parts is optional.
 *
 *  postgresql://
 *  postgresql://localhost
 *  postgresql://localhost:5433
 *  postgresql://localhost/mydb
 *
 * @param url The URL of the PostgreSQL database to connect to.
 * @param username The username used for authentication.
 * @param password The password used for authentication.
 * @param options Optional pool configuration, defaulting to `Driver.Pool.Options`.
 * @param encoders Optional registry of value encoders to use for encoding query parameters.
 */
class PostgreSQL(
    url: String,
    username: String,
    password: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
    override val encoders: Statement.ValueEncoderRegistry = Statement.ValueEncoderRegistry()
) : IPostgresSQL by PostgreSQLImpl(
    connectionPool(options, connectionFactory(url, username, password)),
    connectionFactory(url, username, password),
    encoders
) {
    companion object {
        private fun connectionFactory(url: String, username: String, password: String): PostgresqlConnectionFactory {
            val url = URI(url)
            return PostgresqlConnectionFactory(
                PostgresqlConnectionConfiguration.builder()
                    .host(url.host)
                    .port(url.port.takeIf { it > 0 } ?: 5432)
                    .database(url.path.removePrefix("/"))
                    .username(username)
                    .password(password)
                    .build()
            )
        }

        private fun connectionOptions(
            options: ConnectionPool.Options,
            connectionFactory: PostgresqlConnectionFactory
        ): ConnectionPoolConfiguration {
            return ConnectionPoolConfiguration.builder(connectionFactory).apply {
                options.minConnections?.let { minIdle(it) }
                maxSize(options.maxConnections)
                options.acquireTimeout?.let { maxAcquireTime(it.toJavaDuration()) }
                options.idleTimeout?.let { maxIdleTime(it.toJavaDuration()) }
                options.maxLifetime?.let { maxLifeTime(it.toJavaDuration()) }
            }.build()
        }

        private fun connectionPool(
            options: ConnectionPool.Options,
            connectionFactory: PostgresqlConnectionFactory
        ): R2dbcConnectionPool {
            val poolConfiguration = connectionOptions(options, connectionFactory)
            return R2dbcConnectionPool(poolConfiguration).apply {
                runBlocking { launch { runCatching { warmup().awaitSingle() } } }
            }
        }
    }
}
