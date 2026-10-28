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
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.jvm.optionals.getOrElse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.r2dbc.pool.ConnectionPool as NativeR2dbcConnectionPool
import io.r2dbc.postgresql.api.Notification as NativeR2dbcNotification
import io.r2dbc.spi.Connection as NativeR2dbcConnection

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
        // Read without the mutex by the getters below, possibly from another thread.
        @Volatile
        private var _status: Connection.Status = Connection.Status.Open
        override val status: Connection.Status get() = _status
        @Volatile
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
        // Read without the mutex by the getters below, possibly from another thread.
        @Volatile
        private var _status: Transaction.Status = Transaction.Status.Open
        @Volatile
        private var _commited: Boolean = false
        @Volatile
        private var _rollbacked: Boolean = false
        override val status: Transaction.Status get() = _status
        override val commited: Boolean get() = _commited
        override val rollbacked: Boolean get() = _rollbacked

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                if (commited) return@withLock
                assertIsOpen()
                // Its work is part of this one's: ending this one first would leave it (its savepoint) dangling, and
                // left open, this one held its locks until the connection closed: both are rolled back.
                if (child?.status == Transaction.Status.Open) {
                    rollbackLocked()
                    SQLError(
                        SQLError.Code.Database,
                        "The transaction was rolled back: one nested in it was still open when it was committed."
                    ).raise()
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
                rollbackLocked()
            }
        }

        /** Rolls the transaction back, the one nested in it first; its lock is held. */
        private suspend fun rollbackLocked() {
            // Rolled back with this one.
            child?.let { if (it.status == Transaction.Status.Open) it.rollback().getOrThrow() }
            _status = Transaction.Status.Closed
            if (savepoint != null) {
                rethrowAsSQLError {
                    connection.rollbackTransactionToSavepoint(savepoint).toMono().awaitSingleOrNull()
                    connection.releaseSavepoint(savepoint).toMono().awaitSingleOrNull()
                }
                _rollbacked = true
                return
            }
            connection.endTransaction(closeConnectionAfterTx) {
                rollbackTransaction().toMono().awaitSingleOrNull()
                _rollbacked = true
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
}
