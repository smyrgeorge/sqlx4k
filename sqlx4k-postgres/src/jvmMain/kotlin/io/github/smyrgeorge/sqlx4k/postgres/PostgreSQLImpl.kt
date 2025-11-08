package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.*
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migration
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import io.github.smyrgeorge.sqlx4k.impl.types.DoubleQuotingString
import io.github.smyrgeorge.sqlx4k.impl.types.NoQuotingString
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.Row
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import reactor.pool.PoolAcquireTimeoutException
import reactor.pool.PoolShutdownException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.optionals.getOrElse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import io.r2dbc.pool.ConnectionPool as R2dbcConnectionPool
import io.r2dbc.postgresql.api.Notification as R2dbcNotification
import io.r2dbc.spi.Connection as R2dbcConnection
import io.r2dbc.spi.Result as R2dbcResultSet

class PostgreSQLImpl(
    private val pool: R2dbcConnectionPool,
    private val connectionFactory: PostgresqlConnectionFactory,
    override val encoders: Statement.ValueEncoderRegistry = Statement.ValueEncoderRegistry()
) : IPostgresSQL {
    override suspend fun migrate(
        path: String,
        table: String,
        schema: String?,
        createSchema: Boolean,
        afterStatementExecution: suspend (Statement, Duration) -> Unit,
        afterFileMigration: suspend (Migration, Duration) -> Unit
    ): Result<Migrator.Results> = Migrator.migrate(
        db = this,
        path = path,
        table = table,
        schema = schema,
        createSchema = createSchema,
        dialect = Dialect.PostgreSQL,
        afterStatementExecution = afterStatementExecution,
        afterFileMigration = afterFileMigration
    )

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
        Cn(pool.acquire(), encoders)
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

    override suspend fun begin(): Result<Transaction> = runCatching {
        with(pool.acquire()) {
            try {
                beginTransaction().awaitFirstOrNull()
            } catch (e: Exception) {
                close().awaitFirstOrNull()
                SQLError(SQLError.Code.Database, e.message).ex()
            }
            Tx(this, true, encoders)
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
        fun R2dbcNotification.toNotification(): Notification {
            require(name.isNotBlank()) { "Channel cannot be blank." }
            val value = ResultSet.Row.Column(0, name, "TEXT", parameter)
            return Notification(name, value)
        }

        require(channels.isNotEmpty()) { "Channels cannot be empty." }
        channels.forEach { validateChannelName(it) }

        val sql = channels.joinToString(separator = "\n") {
            Statement.create("LISTEN ?;").bind(0, DoubleQuotingString(it)).render(encoders)
        }

        PgChannelScope.launch {
            var retryCount = 0
            val baseDelay = 100.milliseconds

            // Automatically reconnect if the connection closes.
            while (true) {
                try {
                    val con = connectionFactory.create().awaitSingle()
                    @Suppress("SqlSourceToSinkFlow")
                    con.createStatement(sql)
                        .execute()
                        .flatMap { it.rowsUpdated }
                        .thenMany(con.notifications)
                        .asFlow()
                        .collect { f(it.toNotification()) }
                    con.close().awaitFirstOrNull()
                } catch (_: Exception) {
                    retryCount++
                    delay(baseDelay * retryCount)
                }
            }
        }
    }

    /**
     * Sends a notification to a specific PostgreSQL channel with the given value.
     *
     * This method uses the PostgreSQL `pg_notify` functionality to send a notification
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

    private suspend fun R2dbcConnectionPool.acquire(): R2dbcConnection {
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

    class Cn(
        private val connection: R2dbcConnection,
        override val encoders: Statement.ValueEncoderRegistry
    ) : Connection {
        private val mutex = Mutex()
        private var _status: Connection.Status = Connection.Status.Open
        override val status: Connection.Status get() = _status
        private var _transactionIsolationLevel: Transaction.IsolationLevel? = null
        override val transactionIsolationLevel: Transaction.IsolationLevel? get() = _transactionIsolationLevel

        override suspend fun close(): Result<Unit> = runCatching {
            mutex.withLock {
                assertIsOpen()
                _status = Connection.Status.Closed
                connection.close().awaitFirstOrNull()
            }
            transactionIsolationLevel?.let {
                val default = IPostgresSQL.DEFAULT_TRANSACTION_ISOLATION_LEVEL
                setTransactionIsolationLevel(default)
            }
        }

        override suspend fun setTransactionIsolationLevel(level: Transaction.IsolationLevel): Result<Unit> {
            // language=SQL
            val sql = "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL ?"
            val statement = Statement.create(sql).bind(0, NoQuotingString(level.value))
            return execute(statement).map { }.also { _transactionIsolationLevel = level }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                @Suppress("SqlSourceToSinkFlow")
                connection.createStatement(sql).execute().awaitSingle().rowsUpdated.awaitFirstOrNull() ?: 0
            }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                @Suppress("SqlSourceToSinkFlow")
                connection.createStatement(sql).execute().awaitSingle().toResultSet().toResult()
            }
        }

        override suspend fun begin(): Result<Transaction> = runCatching {
            mutex.withLock {
                assertIsOpen()
                try {
                    connection.beginTransaction().awaitFirstOrNull()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message).ex()
                }
                Tx(connection, false, encoders)
            }
        }
    }

    class Tx(
        private var connection: R2dbcConnection,
        private val closeConnectionAfterTx: Boolean,
        override val encoders: Statement.ValueEncoderRegistry
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

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                @Suppress("SqlSourceToSinkFlow")
                connection.createStatement(sql).execute().awaitSingle().toResultSet().toResult()
            }
        }
    }

    companion object {
        private object PgChannelScope : CoroutineScope {
            override val coroutineContext: CoroutineContext
                get() = EmptyCoroutineContext
        }

        private suspend fun R2dbcResultSet.toResultSet(): ResultSet {
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
