@file:Suppress("SqlNoDataSourceInspection")

package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.Transaction.IsolationLevel
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migration
import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sqlx4k.postgresql.Sqlx4kPostgresResult
import sqlx4k.postgresql.sqlx4k_postgresql_close
import sqlx4k.postgresql.sqlx4k_postgresql_cn_acquire
import sqlx4k.postgresql.sqlx4k_postgresql_cn_fetch_all
import sqlx4k.postgresql.sqlx4k_postgresql_cn_fetch_all_with_params
import sqlx4k.postgresql.sqlx4k_postgresql_cn_query
import sqlx4k.postgresql.sqlx4k_postgresql_cn_query_with_params
import sqlx4k.postgresql.sqlx4k_postgresql_cn_release
import sqlx4k.postgresql.sqlx4k_postgresql_cn_stream_open
import sqlx4k.postgresql.sqlx4k_postgresql_cn_tx_begin
import sqlx4k.postgresql.sqlx4k_postgresql_fetch_all
import sqlx4k.postgresql.sqlx4k_postgresql_fetch_all_with_params
import sqlx4k.postgresql.sqlx4k_postgresql_listen
import sqlx4k.postgresql.sqlx4k_postgresql_of
import sqlx4k.postgresql.sqlx4k_postgresql_pool_idle_size
import sqlx4k.postgresql.sqlx4k_postgresql_pool_size
import sqlx4k.postgresql.sqlx4k_postgresql_query
import sqlx4k.postgresql.sqlx4k_postgresql_query_with_params
import sqlx4k.postgresql.sqlx4k_postgresql_stream_open
import sqlx4k.postgresql.sqlx4k_postgresql_tx_begin
import sqlx4k.postgresql.sqlx4k_postgresql_tx_commit
import sqlx4k.postgresql.sqlx4k_postgresql_tx_fetch_all
import sqlx4k.postgresql.sqlx4k_postgresql_tx_fetch_all_with_params
import sqlx4k.postgresql.sqlx4k_postgresql_tx_query
import sqlx4k.postgresql.sqlx4k_postgresql_tx_query_with_params
import sqlx4k.postgresql.sqlx4k_postgresql_tx_rollback
import sqlx4k.postgresql.sqlx4k_postgresql_tx_stream_open
import sqlx4k.postgresql.sqlx4k_postgresql_unlisten
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration

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
@OptIn(ExperimentalForeignApi::class)
class PostgreSQL(
    url: String,
    username: String,
    password: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
    override val encoders: ValueEncoderRegistry = ValueEncoderRegistry()
) : IPostgresSQL {
    private val rt: CPointer<out CPointed> = sqlx4k_postgresql_of(
        url = url,
        username = username,
        password = password,
        min_connections = options.minConnections ?: -1,
        max_connections = options.maxConnections,
        acquire_timeout_milis = options.acquireTimeout?.inWholeMilliseconds?.toInt() ?: -1,
        idle_timeout_milis = options.idleTimeout?.inWholeMilliseconds?.toInt() ?: -1,
        max_lifetime_milis = options.maxLifetime?.inWholeMilliseconds?.toInt() ?: -1,
    ).rtOrError()

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
        sqlx { c -> sqlx4k_postgresql_close(rt, c, fn) }.throwIfError()
    }

    override fun poolSize(): Int = sqlx4k_postgresql_pool_size(rt)
    override fun poolIdleSize(): Int = sqlx4k_postgresql_pool_idle_size(rt)

    override suspend fun acquire(): Result<Connection> = runCatching {
        sqlx { c -> sqlx4k_postgresql_cn_acquire(rt, c, fn) }.use {
            it.throwIfError()
            SqlxConnection(rt, it.cn!!, encoders)
        }
    }

    override suspend fun execute(sql: String): Result<Long> = runCatching {
        sqlx { c -> sqlx4k_postgresql_query(rt, sql, c, fn) }.rowsAffectedOrError()
    }

    override suspend fun execute(statement: Statement): Result<Long> = runCatching {
        statement.withNativeQuery(encoders) { sql, params, paramsLen ->
            sqlx { c -> sqlx4k_postgresql_query_with_params(rt, sql, params, paramsLen, c, fn) }.rowsAffectedOrError()
        }
    }

    override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
        sqlx { c -> sqlx4k_postgresql_fetch_all(rt, sql, c, fn) }.resultSetOrError()
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> = runCatching {
        statement.withNativeQuery(encoders) { sql, params, paramsLen ->
            sqlx { c -> sqlx4k_postgresql_fetch_all_with_params(rt, sql, params, paramsLen, c, fn) }.resultSetOrError()
        }
    }

    override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = streamFlow(rt) {
        sqlx { c -> sqlx4k_postgresql_stream_open(rt, sql, null, 0, fetchSize, c, fn) }
    }

    override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> = streamFlow(rt) {
        statement.withNativeQuery(encoders) { sql, params, paramsLen ->
            sqlx { c -> sqlx4k_postgresql_stream_open(rt, sql, params, paramsLen, fetchSize, c, fn) }
        }
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        sqlx { c -> sqlx4k_postgresql_tx_begin(rt, c, fn) }.use {
            it.throwIfError()
            SqlxTransaction(rt, it.tx!!, encoders)
        }
    }

    /**
     * Listens to a specific PostgreSQL channel and processes notifications using the provided callback function.
     *
     * This method leverages the PostgreSQL listen/notify mechanism to receive notifications on the specified channel.
     * It delegates the listening task to the `listen` method that supports multiple channels.
     *
     * @param channel The name of the PostgreSQL channel to listen to. This represents a single PostgreSQL listen/notify channel.
     * @param f A callback function that is invoked for each notification received. The function accepts a `Notification` object
     *          containing the channel name and the notification payload.
     */
    override suspend fun listen(channel: String, f: suspend (Notification) -> Unit) {
        listen(listOf(channel), f)
    }

    /**
     * Listens to notifications on the specified PostgreSQL channels and processes them
     * using the provided callback function. The notifications are received via the
     * PostgreSQL listen/notify mechanism.
     *
     * Suspends until the calling coroutine is cancelled, and then stops listening and closes the connection.
     *
     * @param channels A list of channel names to listen to. The list must not be empty.
     *                 Each channel represents a PostgreSQL listen/notify channel.
     * @param f A callback function that is invoked for each notification received.
     *          The function accepts a `Notification` object containing the channel name
     *          and the notification payload.
     * @throws IllegalArgumentException If the `channels` list is empty.
     */
    @OptIn(ExperimentalAtomicApi::class)
    override suspend fun listen(channels: List<String>, f: suspend (Notification) -> Unit): Unit = coroutineScope {
        require(channels.isNotEmpty()) { "Channels cannot be empty." }
        channels.forEach { validateChannelName(it) }

        val channelId: Int = listenerId()
        val channel = Channel<Notification>(capacity = Channel.UNLIMITED)

        // Store the channel.
        while (true) {
            val current = Companion.channels.load()
            if (Companion.channels.compareAndSet(current, current + (channelId to channel))) break
        }

        try {
            // Deliver the notifications in the caller's scope, so they stop with it. A failing callback does not stop
            // the listener (as on the JVM driver).
            launch {
                channel.consumeEach {
                    try {
                        f(it)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
            }

            // Create the listener.
            sqlx { c ->
                sqlx4k_postgresql_listen(
                    rt,
                    channels = channels.joinToString(","),
                    notify_id = channelId,
                    notify = notify,
                    callback = c,
                    `fun` = fn
                )
            }.throwIfError()

            // Listen until the calling coroutine is cancelled.
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                // Stop the listener and close its connection (also when it failed to start), then forget it.
                runCatching { sqlx { c -> sqlx4k_postgresql_unlisten(channelId, c, fn) }.throwIfError() }
                while (true) {
                    val current = Companion.channels.load()
                    if (Companion.channels.compareAndSet(current, current - channelId)) break
                }
                channel.close()
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

    class SqlxConnection(
        private val rt: CPointer<out CPointed>,
        private val cn: CPointer<out CPointed>,
        override val encoders: ValueEncoderRegistry
    ) : Connection {
        private val mutex = Mutex()

        // The transactions begun on this connection (a begin() while one is open nests in it, as a savepoint): they
        // share the mutex (all drive the same native connection), and close() rolls back the ones still open.
        private val transactions = mutableListOf<SqlxTransaction>()
        private var _status: Connection.Status = Connection.Status.Open
        override val status: Connection.Status get() = _status
        private var _transactionIsolationLevel: IsolationLevel? = null
        override val transactionIsolationLevel: IsolationLevel? get() = _transactionIsolationLevel

        // Runs to completion also when the caller is cancelled: waiting for the lock (held by a query still running
        // on the connection) would otherwise throw, and the connection would never go back to the pool.
        override suspend fun close(): Result<Unit> = runCatching {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (status == Connection.Status.Closed) return@withLock
                    _status = Connection.Status.Closed
                    // An open transaction would keep the connection inside it once back in the pool, and its native
                    // handle would outlive the connection it borrows: roll them all back first, innermost first.
                    transactions.asReversed().forEach { runCatching { it.rollbackIfOpenLocked() } }
                    transactions.clear()

                    transactionIsolationLevel?.let {
                        val default = IPostgresSQL.DEFAULT_TRANSACTION_ISOLATION_LEVEL
                        setTransactionIsolationLevel(default, false)
                    }

                    sqlx { c -> sqlx4k_postgresql_cn_release(rt, cn, c, fn) }.throwIfError()
                }
            }
        }

        private suspend fun setTransactionIsolationLevel(level: IsolationLevel, lock: Boolean): Result<Unit> {
            // language=SQL
            val sql = "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL ${level.value}"
            // Only record the new level once the SET has actually succeeded, so the tracked state
            // never diverges from the database (and close() won't attempt a spurious reset).
            return execute(sql, lock).map { }.onSuccess { _transactionIsolationLevel = level }
        }

        override suspend fun setTransactionIsolationLevel(level: IsolationLevel): Result<Unit> =
            setTransactionIsolationLevel(level, true)

        private suspend fun execute(sql: String, lock: Boolean): Result<Long> {
            suspend fun doExecute(): Long =
                sqlx { c -> sqlx4k_postgresql_cn_query(rt, cn, sql, c, fn) }.rowsAffectedOrError()

            return if (lock) mutex.withLockCatching(::assertIsOpen) { doExecute() } else runCatching { doExecute() }
        }

        override suspend fun execute(sql: String): Result<Long> = execute(sql, true)

        override suspend fun execute(statement: Statement): Result<Long> = mutex.withLockCatching(::assertIsOpen) {
            statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                sqlx { c -> sqlx4k_postgresql_cn_query_with_params(rt, cn, sql, params, paramsLen, c, fn) }
                    .rowsAffectedOrError()
            }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = mutex.withLockCatching(::assertIsOpen) {
            sqlx { c -> sqlx4k_postgresql_cn_fetch_all(rt, cn, sql, c, fn) }.resultSetOrError()
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            mutex.withLockCatching(::assertIsOpen) {
                statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                    sqlx { c -> sqlx4k_postgresql_cn_fetch_all_with_params(rt, cn, sql, params, paramsLen, c, fn) }
                        .resultSetOrError()
                }
            }

        // The connection is held (its mutex locked) for the whole collection: a stream keeps the
        // connection busy, exactly as it does in sqlx.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = mutex.flowWithLock(::assertIsOpen) {
            streamFlow(rt) {
                sqlx { c -> sqlx4k_postgresql_cn_stream_open(rt, cn, sql, null, 0, fetchSize, c, fn) }
            }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) {
                streamFlow(rt) {
                    statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                        sqlx { c ->
                            sqlx4k_postgresql_cn_stream_open(rt, cn, sql, params, paramsLen, fetchSize, c, fn)
                        }
                    }
                }
            }

        override suspend fun begin(): Result<Transaction> = mutex.withLockCatching(::assertIsOpen) {
            sqlx { c -> sqlx4k_postgresql_cn_tx_begin(rt, cn, c, fn) }.use {
                it.throwIfError()
                SqlxTransaction(rt, it.tx!!, encoders, mutex).also { tx ->
                    transactions.removeAll { it.status != Transaction.Status.Open }
                    transactions += tx
                }
            }
        }
    }

    class SqlxTransaction internal constructor(
        private val rt: CPointer<out CPointed>,
        private var tx: CPointer<out CPointed>,
        override val encoders: ValueEncoderRegistry,
        // Shared with the connection the transaction was begun on, which drives the same native connection.
        private val mutex: Mutex,
    ) : Transaction {
        constructor(
            rt: CPointer<out CPointed>,
            tx: CPointer<out CPointed>,
            encoders: ValueEncoderRegistry
        ) : this(rt, tx, encoders, Mutex())

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
                sqlx { c -> sqlx4k_postgresql_tx_commit(rt, tx, c, fn) }.throwIfError()
                _commited = true
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                if (rollbacked) return@withLock
                assertIsOpen()
                _status = Transaction.Status.Closed
                sqlx { c -> sqlx4k_postgresql_tx_rollback(rt, tx, c, fn) }.throwIfError()
                _rollbacked = true
            }
        }

        /**
         * Rolls the transaction back if it is still open: called by the connection it was begun on when that is
         * closed, with the shared mutex already held.
         */
        internal suspend fun rollbackIfOpenLocked() {
            if (status != Transaction.Status.Open) return
            _status = Transaction.Status.Closed
            sqlx { c -> sqlx4k_postgresql_tx_rollback(rt, tx, c, fn) }.throwIfError()
            _rollbacked = true
        }

        override suspend fun execute(sql: String): Result<Long> = mutex.withLockCatching(::assertIsOpen) {
            sqlx { c -> sqlx4k_postgresql_tx_query(rt, tx, sql, c, fn) }.useTx { it.toRowsAffectedOrError() }
        }

        override suspend fun execute(statement: Statement): Result<Long> = mutex.withLockCatching(::assertIsOpen) {
            statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                sqlx { c -> sqlx4k_postgresql_tx_query_with_params(rt, tx, sql, params, paramsLen, c, fn) }
                    .useTx { it.toRowsAffectedOrError() }
            }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = mutex.withLockCatching(::assertIsOpen) {
            sqlx { c -> sqlx4k_postgresql_tx_fetch_all(rt, tx, sql, c, fn) }.useTx { it.toResultSetOrError() }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            mutex.withLockCatching(::assertIsOpen) {
                statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                    sqlx { c -> sqlx4k_postgresql_tx_fetch_all_with_params(rt, tx, sql, params, paramsLen, c, fn) }
                        .useTx { it.toResultSetOrError() }
                }
            }

        // The transaction is held (its mutex locked) for the whole collection; the stream borrows the
        // transaction in place, so the `tx` pointer is unchanged afterwards.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = mutex.flowWithLock(::assertIsOpen) {
            streamFlow(rt) {
                sqlx { c -> sqlx4k_postgresql_tx_stream_open(rt, tx, sql, null, 0, fetchSize, c, fn) }
            }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) {
                streamFlow(rt) {
                    statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                        sqlx { c ->
                            sqlx4k_postgresql_tx_stream_open(rt, tx, sql, params, paramsLen, fetchSize, c, fn)
                        }
                    }
                }
            }

        /**
         * Like [use], also taking back the transaction handle: every query call moves the transaction to the Rust
         * side, which hands it back with the result.
         */
        private inline fun <T> CPointer<Sqlx4kPostgresResult>?.useTx(block: (Sqlx4kPostgresResult) -> T): T = use {
            tx = it.tx!!
            block(it)
        }
    }

    companion object {
        // Will eventually overflow, but it doesn't matter, is the desired behavior.
        @OptIn(ExperimentalAtomicApi::class)
        private fun listenerId(): Int = listenerId.incrementAndFetch()

        @OptIn(ExperimentalAtomicApi::class)
        private val listenerId = AtomicInt(0)

        // Written by listen() on the caller's thread and read by notify on the threads of the Rust runtime: an
        // immutable map, replaced atomically (copy on write).
        @OptIn(ExperimentalAtomicApi::class)
        private val channels = AtomicReference<Map<Int, Channel<Notification>>>(emptyMap())

        // Runs on a thread of the Rust runtime. It must not throw (an exception escaping a C callback ends the
        // process), always frees the result, and hands the notifications over in the order they arrive: the channel
        // is unlimited, so trySend never suspends (where one coroutine launched per notification could reorder them).
        @OptIn(ExperimentalAtomicApi::class)
        private val notify = staticCFunction<Int, CPointer<Sqlx4kPostgresResult>?, Unit> { c, r ->
            fun ResultSet.toNotification(): Notification {
                require(rows.size == 1) { "Expected exactly one row, got ${rows.size}" }
                val row: ResultSet.Row = rows[0]
                require(row.size == 1) { "Expected exactly one column, got ${row.size}" }
                val column = row.get(0)
                require(column.type == "TEXT") { "Expected TEXT column, got ${column.type}" }
                return Notification(column.name, column)
            }

            runCatching {
                val notification: Notification = r.use { res -> res.toResultSet() }.toNotification()
                channels.load()[c]?.trySend(notification)
            }
        }

    }
}
