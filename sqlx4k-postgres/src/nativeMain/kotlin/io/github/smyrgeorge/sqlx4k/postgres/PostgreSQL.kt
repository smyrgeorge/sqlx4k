package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.*
import io.github.smyrgeorge.sqlx4k.impl.extensions.*
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
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.experimental.ExperimentalNativeApi

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
 *  postgresql://user@localhost
 *  postgresql://user:secret@localhost
 *  postgresql://localhost?dbname=mydb&user=postgres&password=postgres
 *
 * @param url The URL of the PostgreSQL database to connect to.
 * @param username The username used for authentication.
 * @param password The password used for authentication.
 * @param options Optional pool configuration, defaulting to `Driver.Pool.Options`.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
class PostgreSQL(
    url: String,
    username: String,
    password: String,
    options: Driver.Pool.Options = Driver.Pool.Options(),
) : Driver, Driver.Pool, Driver.Transactional, Driver.Migrate {
    init {
        sqlx4k_of(
            url = url,
            username = username,
            password = password,
            min_connections = options.minConnections ?: -1,
            max_connections = options.maxConnections,
            acquire_timeout_milis = options.acquireTimeout?.inWholeMilliseconds?.toInt() ?: -1,
            idle_timeout_milis = options.idleTimeout?.inWholeMilliseconds?.toInt() ?: -1,
            max_lifetime_milis = options.maxLifetime?.inWholeMilliseconds?.toInt() ?: -1,
        ).throwIfError()
    }

    override suspend fun migrate(path: String): Result<Unit> = runCatching {
        sqlx { c -> sqlx4k_migrate(path, c, Driver.fn) }.throwIfError()
    }

    override suspend fun close(): Result<Unit> = runCatching {
        sqlx { c -> sqlx4k_close(c, Driver.fn) }.throwIfError()
    }

    override fun poolSize(): Int = sqlx4k_pool_size()
    override fun poolIdleSize(): Int = sqlx4k_pool_idle_size()

    override suspend fun execute(sql: String): Result<Long> = runCatching {
        sqlx { c -> sqlx4k_query(sql, c, Driver.fn) }.rowsAffectedOrError()
    }

    override suspend fun execute(statement: Statement): Result<Long> =
        execute(statement.render(encoders))

    override suspend fun fetchAll(sql: String): Result<ResultSet> {
        val res = sqlx { c -> sqlx4k_fetch_all(sql, c, Driver.fn) }
        return res.use { it.toResultSet() }.toResult()
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        fetchAll(statement.render(encoders))

    override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
        fetchAll(statement.render(encoders), rowMapper)

    override suspend fun begin(): Result<Transaction> = runCatching {
        sqlx { c -> sqlx4k_tx_begin(c, Driver.fn) }.use {
            it.throwIfError()
            Tx(it.tx!!)
        }
    }

    suspend fun listen(channel: String, f: (Notification) -> Unit) {
        listen(listOf(channel), f)
    }

    suspend fun listen(channels: List<String>, f: (Notification) -> Unit) {
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
                // TODO: validate channels.
                channels = channels.joinToString(","),
                notify_id = channelId,
                notify = notify,
                callback = c,
                `fun` = Driver.fn
            )
        }.throwIfError()
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
        private var tx: CPointer<out CPointed>
    ) : Transaction {
        private val mutex = Mutex()
        private var _status: Transaction.Status = Transaction.Status.Open
        override val status: Transaction.Status = _status

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                isOpenOrError()
                _status = Transaction.Status.Closed
                sqlx { c -> sqlx4k_tx_commit(tx, c, Driver.fn) }.throwIfError()
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                isOpenOrError()
                _status = Transaction.Status.Closed
                sqlx { c -> sqlx4k_tx_rollback(tx, c, Driver.fn) }.throwIfError()
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                isOpenOrError()
                sqlx { c -> sqlx4k_tx_query(tx, sql, c, Driver.fn) }.use {
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
                sqlx { c -> sqlx4k_tx_fetch_all(tx, sql, c, Driver.fn) }.use {
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

    /**
     * Represents a notification received from a PostgreSQL listen/notify channel.
     *
     * A `Notification` object contains details about a notification event that has
     * been listened to via the PostgreSQL listen/notify mechanism. It holds the
     * associated channel and the actual value of the notification payload.
     *
     * @property channel The name of the PostgreSQL channel from which the notification was received.
     * @property value The payload of the notification represented as a column of a result set.
     */
    data class Notification(
        val channel: String,
        val value: ResultSet.Row.Column,
    )

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
        private fun listenerId(): Int = listenerId.incrementAndGet()
        private val listenerId = AtomicInt(0)

        private val channels: MutableMap<Int, Channel<Notification>> = mutableMapOf()
        private val notify = staticCFunction<Int, CPointer<Sqlx4kResult>?, Unit> { c, r ->
            fun ResultSet.toNotification(): Notification {
                require(rows.size == 1) { "Expected exactly one row, got ${rows.size}" }
                val row: ResultSet.Row = rows[0]
                require(row.size == 1) { "Expected exactly one column, got ${row.size}" }
                val column = row.get(0)
                require(column.type == "TEXT") { "Expected TEXT column, got ${column.type}" }

                return Notification(
                    channel = column.name,
                    value = column,
                )
            }

            channels[c]?.let {
                val notification: Notification = r.use { it.toResultSet() }.toNotification()
                PgChannelScope.launch { it.send(notification) }
            }
        }

        private object PgChannelScope : CoroutineScope {
            override val coroutineContext: CoroutineContext
                get() = EmptyCoroutineContext
        }
    }
}
