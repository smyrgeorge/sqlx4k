package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.*
import io.github.smyrgeorge.sqlx4k.impl.extensions.*
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sqlx4k.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

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
@OptIn(ExperimentalForeignApi::class)
class PostgreSQL(
    url: String,
    username: String,
    password: String,
    options: QueryExecutor.Pool.Options = QueryExecutor.Pool.Options(),
) : IPostgresSQL {
    private val rt: CPointer<out CPointed> = sqlx4k_of(
        url = url,
        username = username,
        password = password,
        min_connections = options.minConnections ?: -1,
        max_connections = options.maxConnections,
        acquire_timeout_milis = options.acquireTimeout?.inWholeMilliseconds?.toInt() ?: -1,
        idle_timeout_milis = options.idleTimeout?.inWholeMilliseconds?.toInt() ?: -1,
        max_lifetime_milis = options.maxLifetime?.inWholeMilliseconds?.toInt() ?: -1,
    ).rtOrError()

    override suspend fun migrate(path: String, table: String): Result<Unit> =
        Migrator.migrate(this, path, table, Dialect.PostgreSQL)

    override suspend fun close(): Result<Unit> = runCatching {
        sqlx { c -> sqlx4k_close(rt, c, DriverNativeUtils.fn) }.throwIfError()
    }

    override fun poolSize(): Int = sqlx4k_pool_size(rt)
    override fun poolIdleSize(): Int = sqlx4k_pool_idle_size(rt)

    override suspend fun acquire(): Result<Connection> = runCatching {
        sqlx { c -> sqlx4k_cn_acquire(rt, c, DriverNativeUtils.fn) }.use {
            it.throwIfError()
            Cn(rt, it.cn!!)
        }
    }

    override suspend fun execute(sql: String): Result<Long> = runCatching {
        sqlx { c -> sqlx4k_query(rt, sql, c, DriverNativeUtils.fn) }.rowsAffectedOrError()
    }

    override suspend fun execute(statement: Statement): Result<Long> =
        execute(statement.render(encoders))

    override suspend fun fetchAll(sql: String): Result<ResultSet> {
        val res = sqlx { c -> sqlx4k_fetch_all(rt, sql, c, DriverNativeUtils.fn) }
        return res.use { it.toResultSet() }.toResult()
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        fetchAll(statement.render(encoders))

    override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
        fetchAll(statement.render(encoders), rowMapper)

    override suspend fun begin(): Result<Transaction> = runCatching {
        sqlx { c -> sqlx4k_tx_begin(rt, c, DriverNativeUtils.fn) }.use {
            it.throwIfError()
            Tx(rt, it.tx!!)
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
    override suspend fun listen(channel: String, f: (Notification) -> Unit) {
        listen(listOf(channel), f)
    }

    /**
     * Listens to notifications on the specified PostgreSQL channels and processes them
     * using the provided callback function. The notifications are received via the
     * PostgreSQL listen/notify mechanism.
     *
     * @param channels A list of channel names to listen to. The list must not be empty.
     *                 Each channel represents a PostgreSQL listen/notify channel.
     * @param f A callback function that is invoked for each notification received.
     *          The function accepts a `Notification` object containing the channel name
     *          and the notification payload.
     * @throws IllegalArgumentException If the `channels` list is empty.
     */
    override suspend fun listen(channels: List<String>, f: (Notification) -> Unit) {
        require(channels.isNotEmpty()) { "Channels cannot be empty." }
        channels.forEach { validateChannelName(it) }

        val channelId: Int = listenerId()
        val channel = Channel<Notification>(capacity = Channel.UNLIMITED)

        // Store the channel.
        Companion.channels[channelId] = channel

        // Start the channel consumer.
        PgChannelScope.launch {
            channel.consumeEach { f(it) }
        }

        // Create the listener.
        sqlx { c ->
            sqlx4k_listen(
                rt,
                channels = channels.joinToString(","),
                notify_id = channelId,
                notify = notify,
                callback = c,
                `fun` = DriverNativeUtils.fn
            )
        }.throwIfError()
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
        val notify = Statement.create("select pg_notify(:chanel, :value);")
            .bind("chanel", channel)
            .bind("value", value)
        execute(notify).getOrThrow()
    }

    /**
     * Represents a database connection implementation that provides capabilities to execute SQL
     * queries, handle transactions, and manage connection state.
     *
     * This class interacts with a native PostgreSQL driver using low-level pointer-based operations,
     * providing asynchronous capabilities through the use of coroutines and suspending functions.
     *
     * @constructor Creates a new instance of the `Cn` class.
     * @param rt A pointer to the runtime environment for the database driver.
     * @param cn A pointer to the database connection.
     */
    class Cn(
        private val rt: CPointer<out CPointed>,
        private val cn: CPointer<out CPointed>
    ) : Connection {
        private val mutex = Mutex()
        private var _status: Connection.Status = Connection.Status.Acquired
        override val status: Connection.Status get() = _status

        override suspend fun release(): Result<Unit> = runCatching {
            mutex.withLock {
                isAcquiredOrError()
                _status = Connection.Status.Released
                sqlx { c -> sqlx4k_cn_release(rt, cn, c, DriverNativeUtils.fn) }.throwIfError()
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                isAcquiredOrError()
                sqlx { c -> sqlx4k_cn_query(rt, cn, sql, c, DriverNativeUtils.fn) }.use {
                    it.throwIfError()
                    it.rows_affected.toLong()
                }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            execute(statement.render(encoders))

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                isAcquiredOrError()
                sqlx { c -> sqlx4k_tx_fetch_all(rt, cn, sql, c, DriverNativeUtils.fn) }
                    .use { it.toResultSet() }
                    .toResult()
            }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            fetchAll(statement.render(encoders))

        override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
            fetchAll(statement.render(encoders), rowMapper)

        override suspend fun begin(): Result<Transaction> = runCatching {
            mutex.withLock {
                isAcquiredOrError()
                sqlx { c -> sqlx4k_cn_tx_begin(rt, cn, c, DriverNativeUtils.fn) }.use {
                    it.throwIfError()
                    Tx(rt, it.tx!!)
                }
            }
        }
    }

    /**
     * Represents a database transaction, providing methods to perform commit, rollback,
     * and query execution operations within the transaction's context.
     *
     * This class models the behavior of a transactional session, ensuring thread-safe execution
     * of operations using a locking mechanism and maintaining the transaction's state.
     *
     * @constructor Creates a new instance of [Tx] bound to the specified transaction pointer.
     * @property tx A pointer to the transaction in the underlying C library.
     */
    class Tx(
        private val rt: CPointer<out CPointed>,
        private var tx: CPointer<out CPointed>
    ) : Transaction {
        private val mutex = Mutex()
        private var _status: Transaction.Status = Transaction.Status.Open
        override val status: Transaction.Status get() = _status

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                isOpenOrError()
                _status = Transaction.Status.Closed
                sqlx { c -> sqlx4k_tx_commit(rt, tx, c, DriverNativeUtils.fn) }.throwIfError()
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                isOpenOrError()
                _status = Transaction.Status.Closed
                sqlx { c -> sqlx4k_tx_rollback(rt, tx, c, DriverNativeUtils.fn) }.throwIfError()
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                isOpenOrError()
                sqlx { c -> sqlx4k_tx_query(rt, tx, sql, c, DriverNativeUtils.fn) }.use {
                    tx = it.tx!!
                    it.throwIfError()
                    it.rows_affected.toLong()
                }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            execute(statement.render(encoders))

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                isOpenOrError()
                sqlx { c -> sqlx4k_tx_fetch_all(rt, tx, sql, c, DriverNativeUtils.fn) }.use {
                    tx = it.tx!!
                    it.toResultSet()
                }.toResult()
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

        // Will eventually overflow, but it doesn't matter, is the desired behaviour.
        @OptIn(ExperimentalAtomicApi::class)
        private fun listenerId(): Int = listenerId.incrementAndFetch()

        @OptIn(ExperimentalAtomicApi::class)
        private val listenerId = AtomicInt(0)

        private val channels: MutableMap<Int, Channel<Notification>> = mutableMapOf()
        private val notify = staticCFunction<Int, CPointer<Sqlx4kResult>?, Unit> { c, r ->
            fun ResultSet.toNotification(): Notification {
                require(rows.size == 1) { "Expected exactly one row, got ${rows.size}" }
                val row: ResultSet.Row = rows[0]
                require(row.size == 1) { "Expected exactly one column, got ${row.size}" }
                val column = row.get(0)
                require(column.type == "TEXT") { "Expected TEXT column, got ${column.type}" }
                return Notification(column.name, column)
            }

            channels[c]?.let {
                val notification: Notification = r.use { res -> res.toResultSet() }.toNotification()
                PgChannelScope.launch { it.send(notification) }
            }
        }

        private object PgChannelScope : CoroutineScope {
            override val coroutineContext: CoroutineContext
                get() = EmptyCoroutineContext
        }
    }
}
