package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.*
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.Row
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import reactor.pool.PoolAcquireTimeoutException
import reactor.pool.PoolShutdownException
import java.net.URI
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.optionals.getOrElse
import kotlin.time.toJavaDuration
import io.r2dbc.spi.Connection as R2dbcConnection

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
class PostgreSQL(
    url: String,
    username: String,
    password: String,
    options: QueryExecutor.Pool.Options = QueryExecutor.Pool.Options(),
) : IPostgresSQL {

    private val connectionFactory: PostgresqlConnectionFactory = connectionFactory(url, username, password)
    private val poolConfiguration: ConnectionPoolConfiguration = connectionOptions(options, connectionFactory)
    private val pool: ConnectionPool = ConnectionPool(poolConfiguration).apply {
        runBlocking { launch { runCatching { warmup().awaitSingle() } } }
    }

    override suspend fun migrate(path: String, table: String): Result<Unit> =
        Migrator.migrate(this, path, table, Dialect.PostgreSQL)

    override suspend fun close(): Result<Unit> = runCatching {
        try {
            pool.disposeLater().awaitFirstOrNull()
        } catch (e: Exception) {
            SQLError(SQLError.Code.WorkerCrashed, e.message).ex()
        }
    }

    override fun poolSize(): Int = pool.metrics.getOrElse { error("No metrics available.") }.allocatedSize()
    override fun poolIdleSize(): Int = pool.metrics.getOrElse { error("No metrics available.") }.idleSize()

    override suspend fun acquire(): Result<Connection> = runCatching {
        Cn(pool.acquire())
    }

    override suspend fun execute(sql: String): Result<Long> = runCatching {
        @Suppress("SqlSourceToSinkFlow")
        with(pool.acquire()) {
            val res = try {
                createStatement(sql).execute().awaitSingle().rowsUpdated.awaitFirstOrNull() ?: 0
            } catch (e: Exception) {
                SQLError(SQLError.Code.Database, e.message).ex()
            } finally {
                close().awaitFirstOrNull()
            }
            res
        }
    }

    override suspend fun execute(statement: Statement): Result<Long> =
        execute(statement.render(encoders))

    override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
        @Suppress("SqlSourceToSinkFlow")
        with(pool.acquire()) {
            val res = try {
                createStatement(sql).execute().awaitSingle().toResultSet()
            } catch (e: Exception) {
                SQLError(SQLError.Code.Database, e.message).ex()
            } finally {
                close().awaitFirstOrNull()
            }
            res
        }
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        fetchAll(statement.render(encoders))

    override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
        fetchAll(statement.render(encoders), rowMapper)

    override suspend fun begin(): Result<Transaction> = runCatching {
        with(pool.acquire()) {
            try {
                beginTransaction().awaitFirstOrNull()
            } catch (e: Exception) {
                close().awaitFirstOrNull()
                SQLError(SQLError.Code.Database, e.message).ex()
            }
            Tx(this, true)
        }
    }

    /**
     * Listens for notifications from a specified PostgreSQL channel.
     *
     * The method establishes a subscription to a PostgreSQL channel using the LISTEN/NOTIFY
     * mechanism. When a notification is received on the subscribed channel, the provided
     * callback function is invoked with the notification object as a parameter. This method
     * delegates the functionality to a variant that supports multiple channels.
     *
     * @param channel The name of the PostgreSQL channel to listen to. Must not be blank.
     * @param f A callback function to handle notifications. The function receives a `Notification` object
     *          containing the channel name and payload.
     * @throws IllegalArgumentException If the `channel` parameter is blank.
     */
    override suspend fun listen(channel: String, f: suspend (Notification) -> Unit) {
        listen(listOf(channel), f)
    }

    /**
     * Listens for notifications from the specified PostgreSQL channels.
     *
     * The method establishes a subscription to one or more PostgreSQL channels using the LISTEN/NOTIFY
     * functionality. When a notification is received on one of the subscribed channels, the provided
     * callback function is invoked with the notification object as a parameter. The method ensures that
     * channel names are valid and automatically attempts to reconnect if the connection is closed.
     *
     * @param channels A list of PostgreSQL channel names to listen to. Must not be empty.
     * @param f A callback function to handle notifications. The function receives a `Notification` object
     *          containing the channel name and payload.
     * @throws IllegalArgumentException If the `channels` list is empty or any channel name is blank.
     */
    override suspend fun listen(channels: List<String>, f: suspend (Notification) -> Unit) {
        fun io.r2dbc.postgresql.api.Notification.toNotification(): Notification {
            require(name.isNotBlank()) { "Channel cannot be blank." }
            val value = ResultSet.Row.Column(0, name, "TEXT", parameter)
            return Notification(name, value)
        }

        require(channels.isNotEmpty()) { "Channels cannot be empty." }
        channels.forEach { validateChannelName(it) }

        val sql = buildString {
            for (chan in channels) {
                append("LISTEN \"")
                append(chan)
                append("\";")
            }
        }

        PgChannelScope.launch {
            while (true) {
                val con = connectionFactory.create().awaitSingle()
                @Suppress("SqlSourceToSinkFlow")
                con.createStatement(sql)
                    .execute()
                    .flatMap { it.rowsUpdated }
                    .thenMany(con.notifications)
                    .asFlow()
                    .collect { f(it.toNotification()) }
                con.close().awaitFirstOrNull()
                // Automatically reconnect if connection closes.
            }
        }
    }

    /**
     * Sends a notification to a specific PostgreSQL channel with the given value.
     *
     * This method utilizes the PostgreSQL `pg_notify` functionality to send a notification
     * to a specified channel. The channel and value are passed as parameters. The channel name
     * must not be blank.
     *
     * @param channel The name of the PostgreSQL channel to send the notification to. Must not be blank.
     * @param value The notification payload to be sent to the specified channel.
     * @throws IllegalArgumentException If the `channel` parameter is blank.
     */
    override suspend fun notify(channel: String, value: String) {
        validateChannelName(channel)
        val notify = Statement.create("select pg_notify(:channel, :value);")
            .bind("channel", channel)
            .bind("value", value)
        execute(notify).getOrThrow()
    }

    private suspend fun ConnectionPool.acquire(): R2dbcConnection {
        return try {
            create().awaitSingle()
        } catch (e: Exception) {
            when (e) {
                is PoolShutdownException -> SQLError(SQLError.Code.PoolClosed, e.message).ex()
                is PoolAcquireTimeoutException -> SQLError(SQLError.Code.PoolTimedOut, e.message).ex()
                else -> SQLError(SQLError.Code.Pool, e.message).ex()
            }
        }
    }

    /**
     * A concrete implementation of the `Connection` interface that manages a single database connection
     * while ensuring thread-safety and proper lifecycle handling.
     *
     * This class wraps an `R2dbcConnection` and provides methods for executing queries, managing transactions,
     * and fetching results. It uses a mutex to synchronize operations and ensures the connection is in the
     * correct state before performing any operations. It tracks the connection's status internally and supports
     * releasing resources appropriately.
     *
     * @constructor Creates an instance of `Cn` with the specified `R2dbcConnection`.
     * @property connection The underlying `R2dbcConnection` used for executing database queries and transactions.
     */
    class Cn(
        private val connection: R2dbcConnection
    ) : Connection {
        private val mutex = Mutex()
        private var _status: Connection.Status = Connection.Status.Acquired
        override val status: Connection.Status get() = _status

        override suspend fun release(): Result<Unit> = runCatching {
            mutex.withLock {
                assertIsAcquired()
                _status = Connection.Status.Released
                connection.close().awaitFirstOrNull()
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsAcquired()
                @Suppress("SqlSourceToSinkFlow")
                connection.createStatement(sql).execute().awaitSingle().rowsUpdated.awaitFirstOrNull() ?: 0
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            execute(statement.render(encoders))

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsAcquired()
                @Suppress("SqlSourceToSinkFlow")
                connection.createStatement(sql).execute().awaitSingle().toResultSet().toResult()
            }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            fetchAll(statement.render(encoders))

        override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
            fetchAll(statement.render(encoders), rowMapper)

        override suspend fun begin(): Result<Transaction> = runCatching {
            mutex.withLock {
                assertIsAcquired()
                try {
                    connection.beginTransaction().awaitFirstOrNull()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message).ex()
                }
                Tx(connection, false)
            }
        }
    }

    /**
     * Represents a database transaction that utilizes a reactive connection for transactional operations.
     *
     * This class implements the [Transaction] interface and provides functionality to manage the lifecycle
     * of a transaction, including committing, rolling back, and executing SQL statements. It ensures thread-safety
     * and consistency using a coroutine-based mutex to synchronize operations on the transaction.
     *
     * @constructor Creates a new transaction instance with a specific database connection.
     * @param connection The reactive database connection used for the transaction.
     * @param closeConnectionAfterTx Indicates whether the connection should be closed after the transaction is finalized.
     */
    class Tx(
        private var connection: R2dbcConnection,
        private val closeConnectionAfterTx: Boolean
    ) : Transaction {
        private val mutex = Mutex()
        private var _status: Transaction.Status = Transaction.Status.Open
        override val status: Transaction.Status get() = _status

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                assertIsOpen()
                _status = Transaction.Status.Closed
                try {
                    connection.commitTransaction().awaitFirstOrNull()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message).ex()
                } finally {
                    if (closeConnectionAfterTx) connection.close().awaitFirstOrNull()
                }
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                assertIsOpen()
                _status = Transaction.Status.Closed
                try {
                    connection.rollbackTransaction().awaitFirstOrNull()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message).ex()
                } finally {
                    if (closeConnectionAfterTx) connection.close().awaitFirstOrNull()
                }
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                @Suppress("SqlSourceToSinkFlow")
                connection.createStatement(sql).execute().awaitSingle().rowsUpdated.awaitFirstOrNull() ?: 0
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            execute(statement.render(encoders))

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                @Suppress("SqlSourceToSinkFlow")
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

        private object PgChannelScope : CoroutineScope {
            override val coroutineContext: CoroutineContext
                get() = EmptyCoroutineContext
        }

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
            options: QueryExecutor.Pool.Options,
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

        private suspend fun io.r2dbc.spi.Result.toResultSet(): ResultSet {
            fun Row.toRow(): ResultSet.Row {
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

            val rows = map { r, _ -> r.toRow() }.asFlow().toList()
            val meta = if (rows.isEmpty()) ResultSet.Metadata(emptyList())
            else rows.first().toMetadata()
            return ResultSet(rows, null, meta)
        }
    }
}
