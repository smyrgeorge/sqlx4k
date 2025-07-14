package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.*
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.Connection
import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import kotlin.String
import kotlin.jvm.optionals.getOrElse
import kotlin.text.String
import kotlin.time.toJavaDuration

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
 */
@Suppress("SqlSourceToSinkFlow")
class PostgreSQL(
    url: String,
    username: String,
    password: String,
    options: Driver.Pool.Options = Driver.Pool.Options(),
) : Driver, Driver.Pool, Driver.Transactional, Driver.Migrate {

    private val pool: ConnectionPool = createConnectionPool(
        url = url,
        username = username,
        password = password,
        options = options
    ).apply {
        runBlocking {
            launch { runCatching { warmup().awaitSingle() } }
        }
    }

    override suspend fun migrate(path: String): Result<Unit> {
        error("This feature is not yet implemented.")
    }

    override suspend fun close(): Result<Unit> = runCatching {
        pool.disposeLater().awaitSingle()
    }

    override fun poolSize(): Int = pool.metrics.getOrElse { error("No metrics available.") }.maxAllocatedSize
    override fun poolIdleSize(): Int = pool.metrics.getOrElse { error("No metrics available.") }.idleSize()

    override suspend fun execute(sql: String): Result<Long> = runCatching {
        pool.create().awaitSingle().createStatement(sql).execute().awaitSingle().rowsUpdated.awaitSingle()
    }

    override suspend fun execute(statement: Statement): Result<Long> =
        execute(statement.render(encoders))

    override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
        pool.create().awaitSingle().createStatement(sql).execute().awaitSingle().toResultSet()
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        fetchAll(statement.render(encoders))

    override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
        fetchAll(statement.render(encoders), rowMapper)

    override suspend fun begin(): Result<Transaction> = runCatching {
        val con: Connection = pool.create().awaitSingle().also { it.beginTransaction().awaitSingle() }
        return Result.success(Tx(con))
    }

    suspend fun listen(channel: String, f: (Notification) -> Unit) {
        listen(listOf(channel), f)
    }

    suspend fun listen(channels: List<String>, f: (Notification) -> Unit) {
        error("This feature is not yet implemented.")
    }

    /**
     * We accept only [String] values,
     * because only the text type is supported by postgres.
     * https://www.postgresql.org/docs/current/sql-notify.html
     */
    suspend fun notify(channel: String, value: String) {
        require(channel.isNotBlank()) { "Channel cannot be blank." }
        val notify = Statement.create("select pg_notify(:chanel, :value);")
            .bind("chanel", channel)
            .bind("value", value)
        execute(notify).getOrThrow()
    }

    /**
     * Represents a transactional context for executing SQL operations with commit and rollback capabilities.
     *
     * This class implements the [Transaction] interface and ensures thread safety through the use of a [Mutex].
     * It provides methods to manage the lifecycle of a transaction, execute SQL operations, and retrieve result sets.
     *
     * @constructor Initializes the transaction with a given database connection.
     * @param connection The database connection associated with this transaction.
     */
    class Tx(
        private var connection: Connection
    ) : Transaction {
        private val mutex = Mutex()
        private var _status: Transaction.Status = Transaction.Status.Open
        override val status: Transaction.Status = _status

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                isOpenOrError()
                _status = Transaction.Status.Closed
                connection.commitTransaction().awaitSingle()
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                isOpenOrError()
                _status = Transaction.Status.Closed
                connection.rollbackTransaction().awaitSingle()
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                isOpenOrError()
                connection.createStatement(sql).execute().awaitSingle().rowsUpdated.awaitSingle()
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            execute(statement.render(encoders))

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                isOpenOrError()
                connection.createStatement(sql).execute().awaitSingle().toResultSet().toResult()
            }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            fetchAll(statement.render(encoders))

        override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
            fetchAll(statement.render(encoders), rowMapper)
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
            url: String,
            username: String,
            password: String,
            options: Driver.Pool.Options
        ): ConnectionPool {
            val url = URI(url)

            val connectionFactory = PostgresqlConnectionFactory(
                PostgresqlConnectionConfiguration.builder()
                    .host(url.host)
                    .port(url.port.takeIf { it > 0 } ?: 5432)
                    .database(url.path.removePrefix("/"))
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

        private fun Row.toRow(): ResultSet.Row {
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

        private suspend fun io.r2dbc.spi.Result.toResultSet(): ResultSet {
            val rows = map { r, _ -> r.toRow() }.asFlow().toList()
            val meta = if (rows.isEmpty()) ResultSet.Metadata(emptyList())
            else rows.first().toMetadata()
            // TODO: handle error?
            return ResultSet(rows, null, meta)
        }
    }
}
