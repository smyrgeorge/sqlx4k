@file:OptIn(ExperimentalUuidApi::class)
@file:Suppress("SqlSourceToSinkFlow", "DuplicatedCode")

package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.Transaction.IsolationLevel
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migration
import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import io.github.smyrgeorge.sqlx4k.impl.types.TypedNull
import io.r2dbc.pool.ConnectionPool as NativeR2dbcConnectionPool
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.postgresql.api.Notification as NativeR2dbcNotification
import io.r2dbc.spi.Connection as NativeR2dbcConnection
import io.r2dbc.spi.Result as NativeR2dbcResultSet
import io.r2dbc.spi.Row
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.optionals.getOrElse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitLast
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toJavaLocalTime
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import reactor.pool.PoolAcquireTimeoutException
import reactor.pool.PoolShutdownException

class PostgreSQLImpl(
    private val pool: NativeR2dbcConnectionPool,
    private val connectionFactory: PostgresqlConnectionFactory,
    override val encoders: ValueEncoderRegistry = ValueEncoderRegistry()
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

    override suspend fun migrate(
        supplier: () -> List<MigrationFile>,
        table: String,
        schema: String?,
        createSchema: Boolean,
        afterStatementExecution: suspend (Statement, Duration) -> Unit,
        afterFileMigration: suspend (Migration, Duration) -> Unit
    ): Result<Migrator.Results> = Migrator.migrate(
        db = this,
        supplier = supplier,
        table = table,
        schema = schema,
        createSchema = createSchema,
        dialect = Dialect.PostgreSQL,
        afterStatementExecution = afterStatementExecution,
        afterFileMigration = afterFileMigration
    )

    override suspend fun migrate(
        files: List<MigrationFile>,
        table: String,
        schema: String?,
        createSchema: Boolean,
        afterStatementExecution: suspend (Statement, Duration) -> Unit,
        afterFileMigration: suspend (Migration, Duration) -> Unit
    ): Result<Migrator.Results> = Migrator.migrate(
        db = this,
        files = files,
        table = table,
        schema = schema,
        createSchema = createSchema,
        dialect = Dialect.PostgreSQL,
        afterStatementExecution = afterStatementExecution,
        afterFileMigration = afterFileMigration
    )

    override suspend fun close(): Result<Unit> = runCatching {
        try {
            pool.disposeLater().awaitSingleOrNull()
        } catch (e: Exception) {
            SQLError(SQLError.Code.WorkerCrashed, e.message, e).raise()
        }
    }

    override fun poolSize(): Int = pool.metrics.getOrElse { error("No metrics available.") }.allocatedSize()
    override fun poolIdleSize(): Int = pool.metrics.getOrElse { error("No metrics available.") }.idleSize()

    override suspend fun acquire(): Result<Connection> = runCatching {
        R2dbcConnection(pool.acquire(), encoders)
    }

    override suspend fun execute(sql: String): Result<Long> = runCatching {
        with(pool.acquire()) {
            val res = try {
                createStatement(sql).execute().awaitLast().rowsUpdated.toMono().awaitSingleOrNull() ?: 0
            } catch (e: Exception) {
                SQLError(SQLError.Code.Database, e.message, e).raise()
            } finally {
                close().toMono().awaitSingleOrNull()
            }
            res
        }
    }

    override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
        with(pool.acquire()) {
            try {
                createStatement(sql).execute().awaitLast().toResultSet()
            } catch (e: Exception) {
                SQLError(SQLError.Code.Database, e.message, e).raise()
            } finally {
                close().toMono().awaitSingleOrNull()
            }
        }
    }

    override suspend fun execute(statement: Statement): Result<Long> = runCatching {
        with(pool.acquire()) {
            val res = try {
                createStatement(statement, encoders).execute().awaitLast().rowsUpdated.toMono().awaitSingleOrNull() ?: 0
            } catch (e: Exception) {
                SQLError(SQLError.Code.Database, e.message, e).raise()
            } finally {
                close().toMono().awaitSingleOrNull()
            }
            res
        }
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> = runCatching {
        with(pool.acquire()) {
            try {
                createStatement(statement, encoders).execute().awaitLast().toResultSet()
            } catch (e: Exception) {
                SQLError(SQLError.Code.Database, e.message, e).raise()
            } finally {
                close().toMono().awaitSingleOrNull()
            }
        }
    }

    override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> = runCatching {
        fetchAll(statement).getOrThrow().let { rowMapper.map(it, encoders) }
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        with(pool.acquire()) {
            try {
                beginTransaction().toMono().awaitSingleOrNull()
            } catch (e: Exception) {
                close().toMono().awaitSingleOrNull()
                SQLError(SQLError.Code.Database, e.message, e).raise()
            }
            R2dbcTransaction(this, true, encoders)
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
        fun NativeR2dbcNotification.toNotification(): Notification {
            require(name.isNotBlank()) { "Channel cannot be blank." }
            val value = ResultSet.Row.Column(0, name, "TEXT", parameter)
            return Notification(name, value)
        }

        require(channels.isNotEmpty()) { "Channels cannot be empty." }
        channels.forEach { validateChannelName(it) }

        val sql = channels.joinToString(separator = "\n") {
            Statement.create("LISTEN ${DoubleQuotingString(it)};").render(encoders)
        }

        PgChannelScope.launch {
            var retryCount = 0
            val baseDelay = 100.milliseconds

            // Automatically reconnect if the connection closes.
            while (true) {
                try {
                    val con = connectionFactory.create().awaitSingle()
                    con.createStatement(sql)
                        .execute()
                        .flatMap { it.rowsUpdated }
                        .thenMany(con.notifications)
                        .asFlow()
                        .collect { f(it.toNotification()) }
                    con.close().awaitSingleOrNull()
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

    private suspend fun NativeR2dbcConnectionPool.acquire(): NativeR2dbcConnection {
        return try {
            create().awaitSingle()
        } catch (e: Exception) {
            when (e) {
                is PoolShutdownException -> SQLError(SQLError.Code.PoolClosed, e.message, e).raise()
                is PoolAcquireTimeoutException -> SQLError(SQLError.Code.PoolTimedOut, e.message, e).raise()
                else -> SQLError(SQLError.Code.Pool, e.message, e).raise()
            }
        }
    }

    class R2dbcConnection(
        private val connection: NativeR2dbcConnection,
        override val encoders: ValueEncoderRegistry
    ) : Connection {
        private val mutex = Mutex()
        private var _status: Connection.Status = Connection.Status.Open
        override val status: Connection.Status get() = _status
        private var _transactionIsolationLevel: IsolationLevel? = null
        override val transactionIsolationLevel: IsolationLevel? get() = _transactionIsolationLevel

        override suspend fun close(): Result<Unit> = runCatching {
            mutex.withLock {
                if (status == Connection.Status.Closed) return@withLock
                _status = Connection.Status.Closed

                transactionIsolationLevel?.let {
                    val default = IPostgresSQL.DEFAULT_TRANSACTION_ISOLATION_LEVEL
                    setTransactionIsolationLevel(default, false).getOrThrow()
                }

                connection.close().toMono().awaitSingleOrNull()
            }
        }

        private suspend fun setTransactionIsolationLevel(level: IsolationLevel, lock: Boolean): Result<Unit> {
            // language=PostgreSQL
            val sql = "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL ${level.value}"
            return execute(sql, lock).map { }.also { _transactionIsolationLevel = level }
        }

        override suspend fun setTransactionIsolationLevel(level: IsolationLevel): Result<Unit> =
            setTransactionIsolationLevel(level, true)

        private suspend fun execute(sql: String, lock: Boolean): Result<Long> {
            suspend fun doExecute(sql: String): Result<Long> = runCatching {
                connection.createStatement(sql).execute().awaitLast().rowsUpdated.toMono().awaitSingleOrNull() ?: 0
            }

            suspend fun doExecuteWithLock(sql: String): Result<Long> = runCatching {
                mutex.withLock {
                    assertIsOpen()
                    doExecute(sql).getOrThrow()
                }
            }

            return try {
                if (lock) doExecuteWithLock(sql) else doExecute(sql)
            } catch (e: Exception) {
                SQLError(SQLError.Code.Database, e.message, e).raise()
            }
        }

        override suspend fun execute(sql: String): Result<Long> = execute(sql, true)

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                try {
                    connection.createStatement(sql).execute().awaitLast().toResultSet().toResult()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message, e).raise()
                }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                try {
                    connection.createStatement(statement, encoders).execute().awaitLast().rowsUpdated.toMono()
                        .awaitSingleOrNull() ?: 0
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message, e).raise()
                }
            }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                try {
                    connection.createStatement(statement, encoders).execute().awaitLast().toResultSet().toResult()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message, e).raise()
                }
            }
        }

        override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
            runCatching {
                fetchAll(statement).getOrThrow().let { rowMapper.map(it, encoders) }
            }

        override suspend fun begin(): Result<Transaction> = runCatching {
            mutex.withLock {
                assertIsOpen()
                try {
                    connection.beginTransaction().toMono().awaitSingleOrNull()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message, e).raise()
                }
                R2dbcTransaction(connection, false, encoders)
            }
        }
    }

    class R2dbcTransaction(
        private val connection: NativeR2dbcConnection,
        private val closeConnectionAfterTx: Boolean,
        override val encoders: ValueEncoderRegistry
    ) : Transaction {
        private val mutex = Mutex()
        private var _status: Transaction.Status = Transaction.Status.Open
        private var _commited: Boolean = false
        private var _rollbacked: Boolean = false
        override val status: Transaction.Status get() = _status
        override val commited: Boolean get() = _commited
        override val rollbacked: Boolean get() = _rollbacked

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                if (commited) return@withLock
                assertIsOpen()
                _status = Transaction.Status.Closed
                try {
                    connection.commitTransaction().toMono().awaitSingleOrNull()
                    _commited = true
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message, e).raise()
                } finally {
                    if (closeConnectionAfterTx) connection.close().toMono().awaitSingleOrNull()
                }
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                if (rollbacked) return@withLock
                assertIsOpen()
                _status = Transaction.Status.Closed
                try {
                    connection.rollbackTransaction().toMono().awaitSingleOrNull()
                    _rollbacked = true
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message, e).raise()
                } finally {
                    if (closeConnectionAfterTx) connection.close().toMono().awaitSingleOrNull()
                }
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                try {
                    connection.createStatement(sql).execute().awaitLast().rowsUpdated.toMono().awaitSingleOrNull() ?: 0
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message, e).raise()
                }
            }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                try {
                    connection.createStatement(sql).execute().awaitLast().toResultSet().toResult()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message, e).raise()
                }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                try {
                    connection.createStatement(statement, encoders).execute().awaitLast().rowsUpdated.toMono()
                        .awaitSingleOrNull() ?: 0
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message, e).raise()
                }
            }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                try {
                    connection.createStatement(statement, encoders).execute().awaitLast().toResultSet().toResult()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message, e).raise()
                }
            }
        }

        override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
            runCatching {
                fetchAll(statement).getOrThrow().let { rowMapper.map(it, encoders) }
            }
    }

    companion object {
        private object PgChannelScope : CoroutineScope {
            override val coroutineContext: CoroutineContext
                get() = EmptyCoroutineContext
        }

        private fun NativeR2dbcConnection.createStatement(
            statement: Statement,
            encoders: ValueEncoderRegistry
        ): io.r2dbc.spi.Statement {
            fun Any.toR2dbc(): Any = when (this) {
                is Instant -> toJavaInstant()
                is LocalDate -> toJavaLocalDate()
                is LocalTime -> toJavaLocalTime()
                is LocalDateTime -> toJavaLocalDateTime()
                is Uuid -> toJavaUuid()
                else -> this
            }

            val query = statement.renderNativeQuery(Dialect.PostgreSQL, encoders)
            val stmt = createStatement(query.sql)
            query.values.forEachIndexed { index, value ->
                when (value) {
                    is TypedNull -> stmt.bindNull(index, value.type.java)
                    null -> stmt.bindNull(index, Any::class.java)
                    else -> stmt.bind(index, value.toR2dbc())
                }
            }
            return stmt
        }

        private fun <T> Publisher<T>.toMono(): Mono<T> {
            if (this is Mono<T>) return this
            error("Publisher is not a Mono: ${this::class.qualifiedName}")
        }

        private suspend fun NativeR2dbcResultSet.toResultSet(): ResultSet {
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
