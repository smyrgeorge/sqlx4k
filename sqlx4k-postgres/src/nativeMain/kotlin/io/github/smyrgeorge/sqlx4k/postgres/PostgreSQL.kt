package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.rowsAffectedOrError
import io.github.smyrgeorge.sqlx4k.impl.extensions.sqlx
import io.github.smyrgeorge.sqlx4k.impl.extensions.throwIfError
import io.github.smyrgeorge.sqlx4k.impl.extensions.tx
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sqlx4k.Sqlx4kResult
import sqlx4k.Sqlx4kRow
import sqlx4k.Sqlx4kSchema
import sqlx4k.sqlx4k_close
import sqlx4k.sqlx4k_fetch_all
import sqlx4k.sqlx4k_free_result
import sqlx4k.sqlx4k_listen
import sqlx4k.sqlx4k_migrate
import sqlx4k.sqlx4k_of
import sqlx4k.sqlx4k_pool_idle_size
import sqlx4k.sqlx4k_pool_size
import sqlx4k.sqlx4k_query
import sqlx4k.sqlx4k_tx_begin
import sqlx4k.sqlx4k_tx_commit
import sqlx4k.sqlx4k_tx_fetch_all
import sqlx4k.sqlx4k_tx_query
import sqlx4k.sqlx4k_tx_rollback
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
@Suppress("MemberVisibilityCanBePrivate", "unused")
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
        Result.success(Unit)
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
        return ResultSet(res).toResult()
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        fetchAll(statement.render(encoders))

    override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
        fetchAll(statement.render(encoders), rowMapper)

    override suspend fun begin(): Result<Transaction> = runCatching {
        val tx = sqlx { c -> sqlx4k_tx_begin(c, Driver.fn) }.tx()
        Tx(tx.first)
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
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
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
        execute("select pg_notify('$channel', '$value');").getOrThrow()
    }

    class Tx(
        override var tx: CPointer<out CPointed>
    ) : Transaction {
        private val mutex = Mutex()
        override var status: Transaction.Status = Transaction.Status.Open

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                isOpenOrError()
                status = Transaction.Status.Closed
                sqlx { c -> sqlx4k_tx_commit(tx, c, Driver.fn) }.throwIfError()
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                isOpenOrError()
                status = Transaction.Status.Closed
                sqlx { c -> sqlx4k_tx_rollback(tx, c, Driver.fn) }.throwIfError()
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                isOpenOrError()
                val res = sqlx { c -> sqlx4k_tx_query(tx, sql, c, Driver.fn) }.tx()
                tx = res.first
                res.second.toLong()
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            execute(statement.render(encoders))

        override suspend fun fetchAll(sql: String): Result<ResultSet> {
            val res = mutex.withLock {
                isOpenOrError()
                val r = sqlx { c -> sqlx4k_tx_fetch_all(tx, sql, c, Driver.fn) }
                ResultSet(r)
            }

            tx = res.getRaw().tx!!
            return res.toResult()
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            fetchAll(statement.render(encoders))

        override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
            fetchAll(statement.render(encoders), rowMapper)
    }

    data class Notification(
        val channel: String,
        val value: String,
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

        private val channels: MutableMap<Int, Channel<Notification>> by lazy { mutableMapOf() }
        private val listenerMutex = Mutex()
        private var listenerId: Int = 0
        private suspend fun listenerId(): Int = listenerMutex.withLock {
            // Will eventually overflow, but it doesn't matter, is the desired behaviour.
            listenerId += 1
            listenerId
        }

        private fun CPointer<Sqlx4kResult>?.notify(): Notification {
            return try {
                val result: Sqlx4kResult =
                    this?.pointed ?: error("Could not extract the value from the raw pointer (null).")

                val schema: Sqlx4kSchema =
                    this.pointed.schema?.pointed ?: error("Could not extract the value from the raw pointer (null).")

                assert(result.size == 1)
                val row: Sqlx4kRow = result.rows!![0]
                assert(row.size == 1)
                assert(schema.columns!![0].kind!!.toKString() == "TEXT")

                Notification(
                    channel = schema.columns!![0].name!!.toKString(),
                    value = row.columns!![0].value!!.toKString()
                )
            } finally {
                sqlx4k_free_result(this)
            }
        }

        private val notify = staticCFunction<Int, CPointer<Sqlx4kResult>?, Unit> { c, r ->
            channels[c]?.let {
                val notification: Notification = r.notify()
                runBlocking { it.send(notification) }
            }
        }
    }
}
