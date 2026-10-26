@file:Suppress("SqlSourceToSinkFlow", "SqlNoDataSourceInspection")

package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.Transaction.IsolationLevel
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migration
import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import io.github.smyrgeorge.sqlx4k.impl.types.TypedNull
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.Row
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toJavaLocalTime
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.optionals.getOrElse
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import io.r2dbc.pool.ConnectionPool as NativeR2dbcConnectionPool
import io.r2dbc.postgresql.api.Notification as NativeR2dbcNotification
import io.r2dbc.spi.Connection as NativeR2dbcConnection
import io.r2dbc.spi.Result as NativeR2dbcResultSet

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

    override suspend fun execute(sql: String): Result<Long> =
        pool.withConnection { createStatement(sql).executeForRowsUpdated() }

    override suspend fun fetchAll(sql: String): Result<ResultSet> =
        pool.withConnection { createStatement(sql).fetchResultSet() }

    override suspend fun execute(statement: Statement): Result<Long> =
        pool.withConnection { createStatement(statement, encoders).executeForRowsUpdated() }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        pool.withConnection { createStatement(statement, encoders).fetchResultSet() }

    override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> =
        pool.flowWithConnection { createStatement(sql).fetchSize(fetchSize).streamRows() }

    override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
        pool.flowWithConnection { createStatement(statement, encoders).fetchSize(fetchSize).streamRows() }

    override suspend fun begin(): Result<Transaction> = runCatching {
        with(pool.acquire()) {
            try {
                beginTransaction().toMono().awaitSingleOrNull()
            } catch (e: Exception) {
                withContext(NonCancellable) { close().toMono().awaitSingleOrNull() }
                e.toSQLError().raise()
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
            // LISTEN's channel is a SQL identifier, not a bindable value (PostgreSQL rejects
            // `LISTEN $1`), so it is validated and double-quoted rather than bound. The statement
            // is still built and rendered through the prepared-statement API for consistency.
            Statement.create("LISTEN ${DoubleQuotingString(it)};")
                .renderNativeQuery(Dialect.PostgreSQL, encoders).sql
        }

        PgChannelScope.launch {
            var retryCount = 0
            val baseDelay = 100.milliseconds
            val maxDelay = 30.seconds

            // Automatically reconnect if the connection closes.
            while (true) {
                try {
                    val con = connectionFactory.create().awaitSingle()
                    // A successful connection resets the reconnect backoff.
                    retryCount = 0
                    try {
                        con.createStatement(sql)
                            .execute()
                            .flatMap { it.rowsUpdated }
                            .thenMany(con.notifications)
                            .asFlow()
                            .collect { f(it.toNotification()) }
                    } finally {
                        // Always release the connection, including when the stream fails mid-flight.
                        con.close().awaitSingleOrNull()
                    }
                } catch (_: Exception) {
                    retryCount++
                    delay(minOf(baseDelay * retryCount, maxDelay))
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
        @Suppress("SqlResolve")
        val notify = Statement.create("select pg_notify(:channel, :value);")
            .bind("channel", channel)
            .bind("value", value)
        execute(notify).getOrThrow()
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

        // Runs to completion also when the caller is cancelled: in a cancelled coroutine the release would be
        // cancelled before reaching the pool, and the connection would stay checked out for good.
        override suspend fun close(): Result<Unit> = runCatching {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (status == Connection.Status.Closed) return@withLock
                    _status = Connection.Status.Closed

                    try {
                        transactionIsolationLevel?.let {
                            val default = IPostgresSQL.DEFAULT_TRANSACTION_ISOLATION_LEVEL
                            setTransactionIsolationLevel(default, false).getOrThrow()
                        }
                    } finally {
                        // Always release the underlying connection, even if the isolation-level reset fails.
                        connection.close().toMono().awaitSingleOrNull()
                    }
                }
            }
        }

        private suspend fun setTransactionIsolationLevel(level: IsolationLevel, lock: Boolean): Result<Unit> {
            // language=PostgreSQL
            val sql = "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL ${level.value}"
            // Only record the new level once the SET has actually succeeded, so the tracked state
            // never diverges from the database (and close() won't attempt a spurious reset).
            return execute(sql, lock).map { }.onSuccess { _transactionIsolationLevel = level }
        }

        override suspend fun setTransactionIsolationLevel(level: IsolationLevel): Result<Unit> =
            setTransactionIsolationLevel(level, true)

        private suspend fun execute(sql: String, lock: Boolean): Result<Long> =
            if (lock) mutex.withLockCatching(::assertIsOpen) { connection.createStatement(sql).executeForRowsUpdated() }
            else runCatching { rethrowAsSQLError { connection.createStatement(sql).executeForRowsUpdated() } }

        override suspend fun execute(sql: String): Result<Long> = execute(sql, true)

        override suspend fun fetchAll(sql: String): Result<ResultSet> = mutex.withLockCatching(::assertIsOpen) {
            connection.createStatement(sql).fetchResultSet()
        }

        override suspend fun execute(statement: Statement): Result<Long> = mutex.withLockCatching(::assertIsOpen) {
            connection.createStatement(statement, encoders).executeForRowsUpdated()
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            mutex.withLockCatching(::assertIsOpen) {
                connection.createStatement(statement, encoders).fetchResultSet()
            }

        // The connection is held (its mutex locked) for the whole collection.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) { connection.createStatement(sql).fetchSize(fetchSize).streamRows() }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) {
                connection.createStatement(statement, encoders).fetchSize(fetchSize).streamRows()
            }

        override suspend fun begin(): Result<Transaction> = mutex.withLockCatching(::assertIsOpen) {
            connection.beginTransaction().toMono().awaitSingleOrNull()
            R2dbcTransaction(connection, false, encoders)
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
                connection.endTransaction(closeConnectionAfterTx) {
                    commitTransaction().toMono().awaitSingleOrNull()
                    _commited = true
                }
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                if (rollbacked) return@withLock
                assertIsOpen()
                _status = Transaction.Status.Closed
                connection.endTransaction(closeConnectionAfterTx) {
                    rollbackTransaction().toMono().awaitSingleOrNull()
                    _rollbacked = true
                }
            }
        }

        override suspend fun execute(sql: String): Result<Long> =
            mutex.withLockCatching(::assertIsOpen) { connection.createStatement(sql).executeForRowsUpdated() }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = mutex.withLockCatching(::assertIsOpen) {
            connection.createStatement(sql).fetchResultSet()
        }

        override suspend fun execute(statement: Statement): Result<Long> = mutex.withLockCatching(::assertIsOpen) {
            connection.createStatement(statement, encoders).executeForRowsUpdated()
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            mutex.withLockCatching(::assertIsOpen) {
                connection.createStatement(statement, encoders).fetchResultSet()
            }

        // The transaction is held (its mutex locked) for the whole collection.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) { connection.createStatement(sql).fetchSize(fetchSize).streamRows() }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) {
                connection.createStatement(statement, encoders).fetchSize(fetchSize).streamRows()
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

            // Kotlin stdlib / kotlinx-datetime types must be mapped to their java.time equivalents,
            // so r2dbc (which only understands java.time.*) can resolve the SQL type for bindNull.
            fun KClass<*>.toR2dbcType(): Class<*> = when (this) {
                Instant::class -> Class.forName("java.time.Instant")
                LocalDate::class -> Class.forName("java.time.LocalDate")
                LocalTime::class -> Class.forName("java.time.LocalTime")
                LocalDateTime::class -> Class.forName("java.time.LocalDateTime")
                Uuid::class -> Class.forName("java.util.UUID")
                else -> this.java
            }

            val query = statement.renderNativeQuery(Dialect.PostgreSQL, encoders)
            val stmt = createStatement(query.sql)
            query.values.forEachIndexed { index, value ->
                when (value) {
                    is TypedNull -> stmt.bindNull(index, value.type.toR2dbcType())
                    null -> stmt.bindNull(index, Any::class.java)
                    else -> stmt.bind(index, value.toR2dbc())
                }
            }
            return stmt
        }

        /**
         * Executes the statement and reports its update count (0 when the statement reports none), summed over its
         * results. Every result is consumed: see fetchResultSet.
         */
        private suspend fun io.r2dbc.spi.Statement.executeForRowsUpdated(): Long {
            var rows = 0L
            execute().asFlow().collect { result -> rows += result.rowsUpdated.toMono().awaitSingleOrNull() ?: 0 }
            return rows
        }

        /**
         * Executes the statement and reads the rows of its last result. Every result is consumed, in order, as it
         * arrives: a statement can produce several (PostgreSQL's SET produces two), and a result left unconsumed
         * (awaitLast() only kept the last one) left the connection out of step, so the error of the next statement
         * on it was lost and reported as a success.
         */
        private suspend fun io.r2dbc.spi.Statement.fetchResultSet(): ResultSet {
            var last: ResultSet? = null
            execute().asFlow().collect { result -> last = result.toResultSet() }
            return last ?: ResultSet(emptyList(), null, ResultSet.Metadata(emptyList()))
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

        /**
         * Streams the rows of the statement with reactive backpressure (R2DBC publishes the rows as the
         * server sends them); a driver failure fails the collection with the matching [SQLError].
         */
        private fun io.r2dbc.spi.Statement.streamRows(): Flow<ResultSet.Row> = flow {
            execute().asFlow().collect { result ->
                emitAll(result.map { row, _ -> row.toRow() }.asFlow())
            }
        }.catch { e -> throw (e as? SQLError) ?: e.toSQLError() }

        private suspend fun NativeR2dbcResultSet.toResultSet(): ResultSet {
            val rows = map { r, _ -> r.toRow() }.asFlow().toList()
            val meta = if (rows.isEmpty()) ResultSet.Metadata(emptyList())
            else rows.first().toMetadata()
            return ResultSet(rows, null, meta)
        }
    }
}
