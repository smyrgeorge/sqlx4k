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
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQLImpl.Companion.toPgArrayText
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.Row
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.reactive.asFlow
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
     * Suspends until the calling coroutine is cancelled, and then stops listening and closes the connection.
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

        var retryCount = 0
        val baseDelay = 100.milliseconds
        val maxDelay = 30.seconds

        // Listen until the calling coroutine is cancelled, reconnecting if the connection closes.
        while (true) {
            try {
                // Closed if it opens just as the listener is cancelled.
                val con = connectionFactory.create().awaitConnection()
                // A successful connection resets the reconnect backoff.
                retryCount = 0
                try {
                    con.createStatement(sql)
                        .execute()
                        .flatMap { it.rowsUpdated }
                        .thenMany(con.notifications)
                        // The driver drops the subscription (best-effort sink) when notifications arrive faster than
                        // they are requested: buffer them, so a burst or a slow callback loses none.
                        .onBackpressureBuffer()
                        .asFlow()
                        .collect {
                            // A failing callback must not stop listening (dropping the connection, and every
                            // notification buffered or sent until it reconnects), as on the native driver.
                            try {
                                f(it.toNotification())
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                            }
                        }
                } finally {
                    // Always release the connection, including when the stream fails mid-flight or the listener is
                    // cancelled (a release in the cancelled coroutine would itself be cancelled).
                    withContext(NonCancellable) { con.close().awaitSingleOrNull() }
                }
            } catch (e: CancellationException) {
                // The listener was cancelled: stop. (One thrown by the callback is handled like any failure.)
                if (!currentCoroutineContext().isActive) throw e
                retryCount++
                delay(minOf(baseDelay * retryCount, maxDelay))
            } catch (_: Exception) {
                retryCount++
                delay(minOf(baseDelay * retryCount, maxDelay))
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

        // The last transaction begun on this connection: close() rolls back the one it is nested in, if it is still
        // open, and a begin() nests in it (or in the one it is nested in) while it is open.
        private var transaction: R2dbcTransaction? = null

        // Runs to completion also when the caller is cancelled: in a cancelled coroutine the release would be
        // cancelled before reaching the pool, and the connection would stay checked out for good.
        override suspend fun close(): Result<Unit> = runCatching {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (status == Connection.Status.Closed) return@withLock
                    _status = Connection.Status.Closed

                    // End an open transaction first: the isolation level reset below would otherwise run inside it,
                    // and the pool's rollback on release would undo it (SET is transactional in PostgreSQL), leaving
                    // the next borrower with this connection's isolation level.
                    transaction?.outermost?.let { if (it.status == Transaction.Status.Open) it.rollback() }
                    transaction = null

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

        // A begin() while a transaction is open nests in it, as a savepoint, as on the native driver: a second BEGIN
        // does nothing in a transaction, so the nested one's commit committed the whole of it, and the outer one's
        // rollback then had nothing left to roll back.
        override suspend fun begin(): Result<Transaction> = mutex.withLockCatching(::assertIsOpen) {
            var parent = transaction
            while (parent != null && parent.status != Transaction.Status.Open) parent = parent.parent
            if (parent == null) {
                connection.beginTransaction().toMono().awaitSingleOrNull()
                R2dbcTransaction(connection, false, encoders)
            } else {
                val savepoint = "sqlx4k_savepoint_${parent.depth}"
                rethrowAsSQLError { connection.createSavepoint(savepoint).toMono().awaitSingleOrNull() }
                R2dbcTransaction(connection, false, encoders, savepoint, parent).also { parent.child = it }
            }.also { transaction = it }
        }
    }

    class R2dbcTransaction(
        private val connection: NativeR2dbcConnection,
        private val closeConnectionAfterTx: Boolean,
        override val encoders: ValueEncoderRegistry,
        // For a transaction nested in another one (begun on a connection in a transaction): its savepoint, and the
        // transaction it is nested in.
        private val savepoint: String? = null,
        internal val parent: R2dbcTransaction? = null,
    ) : Transaction {
        private val mutex = Mutex()

        // The transaction nested in this one, if any.
        internal var child: R2dbcTransaction? = null
        internal val depth: Int get() = (parent?.depth ?: 0) + 1
        internal val outermost: R2dbcTransaction get() = parent?.outermost ?: this
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
                // Its work is part of this one's: ending this one first would leave it (its savepoint) dangling.
                if (child?.status == Transaction.Status.Open) {
                    SQLError(SQLError.Code.Database, "Cannot commit a transaction while one nested in it is still open.")
                        .raise()
                }
                _status = Transaction.Status.Closed
                if (savepoint != null) {
                    rethrowAsSQLError { connection.releaseSavepoint(savepoint).toMono().awaitSingleOrNull() }
                    _commited = true
                    return@withLock
                }
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
                // Rolled back with this one.
                child?.let { if (it.status == Transaction.Status.Open) it.rollback().getOrThrow() }
                _status = Transaction.Status.Closed
                if (savepoint != null) {
                    rethrowAsSQLError {
                        connection.rollbackTransactionToSavepoint(savepoint).toMono().awaitSingleOrNull()
                        connection.releaseSavepoint(savepoint).toMono().awaitSingleOrNull()
                    }
                    _rollbacked = true
                    return@withLock
                }
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
                val type = c.type.name
                ResultSet.Row.Column(
                    ordinal = i,
                    name = c.name,
                    type = type,
                    value = when (type) {
                        in TEXT_ARRAY_TYPES -> get(i, Array<String?>::class.java)?.toPgArrayText()
                        // In the hex form (`\x6100`): with `bytea_output = 'escape'` the server writes `a\000`, which
                        // the decoders (as the native driver's output) do not read, and r2dbc does not decode.
                        "BYTEA" -> get(i, String::class.java)?.let { if (it.startsWith("\\x")) it else it.byteaEscapeToHex() }
                        else -> get(i, String::class.java)
                    }
                )
            }
            return ResultSet.Row(columns)
        }

        /** Converts bytea's escape output form (`\\` is a backslash, `\ooo` an octal byte) to the hex form. */
        private fun String.byteaEscapeToHex(): String = buildString(length * 2 + 2) {
            append("\\x")
            val text = this@byteaEscapeToHex
            var i = 0
            while (i < text.length) {
                val byte = when {
                    text[i] != '\\' -> text[i++].code
                    text.getOrNull(i + 1) == '\\' -> '\\'.code.also { i += 2 }
                    else -> text.substring(i + 1, i + 4).toInt(8).also { i += 4 }
                }
                append(byte.toByte().toHexString())
            }
        }

        /**
         * Text-like array types. r2dbc-postgresql cannot decode them to a String ("Dimensions mismatch"), so they are
         * read as arrays and rendered like the native driver does (see [toPgArrayText]).
         */
        private val TEXT_ARRAY_TYPES = setOf("TEXT_ARRAY", "VARCHAR_ARRAY", "BPCHAR_ARRAY", "NAME_ARRAY")

        /**
         * Renders the array as PostgreSQL writes it (`{a,"b,c",NULL}`), as the native driver does: an element that is
         * empty, reads `NULL`, or holds a delimiter, quote, backslash or whitespace is quoted, so that the `as*Array`
         * decoders read it back as it is.
         */
        private fun Array<String?>.toPgArrayText(): String = joinToString(",", "{", "}") { it?.toPgArrayElement() ?: "NULL" }

        private fun String.toPgArrayElement(): String {
            val quote = isEmpty() || equals("NULL", ignoreCase = true) ||
                any { it == '{' || it == '}' || it == ',' || it == '"' || it == '\\' || it.isWhitespace() }
            if (!quote) return this
            return buildString(length + 2) {
                append('"')
                this@toPgArrayElement.forEach { if (it == '"' || it == '\\') append('\\'); append(it) }
                append('"')
            }
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
