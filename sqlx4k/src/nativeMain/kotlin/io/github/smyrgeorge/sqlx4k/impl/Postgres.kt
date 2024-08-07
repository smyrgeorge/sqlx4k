package io.github.smyrgeorge.sqlx4k.impl

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Driver.Companion.fn
import io.github.smyrgeorge.sqlx4k.Sqlx4k
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
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
import librust_lib.Sqlx4kResult
import librust_lib.Sqlx4kRow
import librust_lib.TYPE_TEXT
import librust_lib.sqlx4k_fetch_all
import librust_lib.sqlx4k_free_result
import librust_lib.sqlx4k_listen
import librust_lib.sqlx4k_of
import librust_lib.sqlx4k_query
import librust_lib.sqlx4k_tx_begin
import librust_lib.sqlx4k_tx_commit
import librust_lib.sqlx4k_tx_fetch_all
import librust_lib.sqlx4k_tx_query
import librust_lib.sqlx4k_tx_rollback
import librust_lib.sqlx4k_pool_size
import librust_lib.sqlx4k_pool_idle_size
import kotlin.experimental.ExperimentalNativeApi

@Suppress("MemberVisibilityCanBePrivate")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
class Postgres(
    host: String,
    port: Int,
    username: String,
    password: String,
    database: String,
    maxConnections: Int
) : Driver, Driver.Tx {
    init {
        sqlx4k_of(
            host = host,
            port = port,
            username = username,
            password = password,
            database = database,
            max_connections = maxConnections
        ).throwIfError()
    }

    fun poolSize(): Int = sqlx4k_pool_size()
    fun poolIdleSize(): Int = sqlx4k_pool_idle_size()

    override suspend fun query(sql: String): Result<Unit> = runCatching {
        sqlx { c -> sqlx4k_query(sql, c, fn) }.throwIfError()
    }

    override suspend fun <T> fetchAll(sql: String, mapper: Sqlx4k.Row.() -> T): Result<List<T>> = runCatching {
        sqlx { c -> sqlx4k_fetch_all(sql, c, fn) }.map { mapper(this) }
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        val tx = sqlx { c -> sqlx4k_tx_begin(c, fn) }.tx()
        Tx(tx)
    }

    suspend fun listen(channel: String, f: (Notification) -> Unit) {
        listen(listOf(channel), f)
    }

    suspend fun listen(channels: List<String>, f: (Notification) -> Unit) {
        val channelId: Long = listenerId()
        val channel = Channel<Notification>(capacity = Channel.UNLIMITED)

        // Store the channel.
        Postgres.channels[channelId] = channel

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
                `fun` = fn
            )
        }.throwIfError()
    }

    /**
     * We accept only [String] values,
     * because only the text type is supported by postgres.
     * https://www.postgresql.org/docs/current/sql-notify.html
     */
    suspend fun notify(channel: String, value: String) {
        query("select pg_notify('$channel', '$value');").getOrThrow()
    }

    class Tx(override var tx: CPointer<out CPointed>) : Transaction {
        private val mutex = Mutex()

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                sqlx { c -> sqlx4k_tx_commit(tx, c, fn) }.throwIfError()
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                sqlx { c -> sqlx4k_tx_rollback(tx, c, fn) }.throwIfError()
            }
        }

        override suspend fun query(sql: String): Result<Unit> = runCatching {
            mutex.withLock {
                tx = sqlx { c -> sqlx4k_tx_query(tx, sql, c, fn) }.tx()
            }
        }

        override suspend fun <T> fetchAll(sql: String, mapper: Sqlx4k.Row.() -> T): Result<List<T>> = runCatching {
            mutex.withLock {
                sqlx { c -> sqlx4k_tx_fetch_all(tx, sql, c, fn) }
                    .txMap { mapper(this) }
                    .also { tx = it.first }
                    .second
            }
        }
    }

    data class Notification(
        val channel: String,
        val value: String,
    )

    companion object {
        private val channels: MutableMap<Long, Channel<Notification>> by lazy { mutableMapOf() }
        private val listenerMutex = Mutex()
        private var listenerId: Long = 0
        private suspend fun listenerId(): Long = listenerMutex.withLock {
            listenerId += 1
            listenerId
        }

        private fun CPointer<Sqlx4kResult>?.notify(): Notification {
            return try {
                val result: Sqlx4kResult =
                    this?.pointed ?: error("Could not extract the value from the raw pointer (null).")

                assert(result.size == 1)
                val row: Sqlx4kRow = result.rows!![0]
                assert(row.size == 1)
                val column = row.columns!![0]
                assert(column.kind == TYPE_TEXT)

                Notification(
                    channel = column.name!!.toKString(),
                    value = column.value!!.readBytes(column.size).toKString()
                )
            } finally {
                sqlx4k_free_result(this)
            }
        }

        private val notify = staticCFunction<Long, CPointer<Sqlx4kResult>?, Unit> { c, r ->
            channels[c]?.let {
                val notification: Notification = r.notify()
                runBlocking { it.send(notification) }
            }
        }
    }
}
