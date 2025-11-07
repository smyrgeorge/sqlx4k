package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.*
import io.github.smyrgeorge.sqlx4k.impl.extensions.*
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migration
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sqlx4k.*
import kotlin.time.Duration

/**
 * The `MySQL` class provides a driver implementation for interacting with a MySQL database.
 * It supports connection pooling, transactional operations, and executing SQL queries.
 *
 *  The connection URL should follow the nex pattern,
 *  as described by [MySQL](https://dev.mysql.com/doc/connector-j/8.0/en/connector-j-reference-jdbc-url-format.html).
 *
 *  The generic format of the connection URL:
 *  mysql://[host][/database][?properties]
 *
 * @param url The connection URL for the MySQL database.
 * @param username The username for authenticating with the database.
 * @param password The password for authenticating with the database.
 * @param options The optional configuration for the connection pool, such as min/max connections and timeout settings.
 * @param encoders Optional registry of value encoders to use for encoding query parameters.
 */
@OptIn(ExperimentalForeignApi::class)
class MySQL(
    url: String,
    username: String,
    password: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
    override val encoders: Statement.ValueEncoderRegistry = Statement.ValueEncoderRegistry()
) : IMySQL {

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
        dialect = Dialect.MySQL,
        afterStatementExecution = afterStatementExecution,
        afterFileMigration = afterFileMigration
    )

    override suspend fun close(): Result<Unit> = runCatching {
        sqlx { c -> sqlx4k_close(rt, c, DriverNativeUtils.fn) }.throwIfError()
    }

    override fun poolSize(): Int = sqlx4k_pool_size(rt)
    override fun poolIdleSize(): Int = sqlx4k_pool_idle_size(rt)

    override suspend fun acquire(): Result<Connection> = runCatching {
        sqlx { c -> sqlx4k_cn_acquire(rt, c, DriverNativeUtils.fn) }.use {
            it.throwIfError()
            Cn(rt, it.cn!!, encoders)
        }
    }

    override suspend fun execute(sql: String): Result<Long> = runCatching {
        sqlx { c -> sqlx4k_query(rt, sql, c, DriverNativeUtils.fn) }.rowsAffectedOrError()
    }

    override suspend fun fetchAll(sql: String): Result<ResultSet> {
        val res = sqlx { c -> sqlx4k_fetch_all(rt, sql, c, DriverNativeUtils.fn) }
        return res.use { it.toResultSet() }.toResult()
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        sqlx { c -> sqlx4k_tx_begin(rt, c, DriverNativeUtils.fn) }.use {
            it.throwIfError()
            Tx(rt, it.tx!!, encoders)
        }
    }

    class Cn(
        private val rt: CPointer<out CPointed>,
        private val cn: CPointer<out CPointed>,
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
                sqlx { c -> sqlx4k_cn_release(rt, cn, c, DriverNativeUtils.fn) }.throwIfError()
            }
            transactionIsolationLevel?.let {
                val default = IMySQL.DEFAULT_TRANSACTION_ISOLATION_LEVEL
                setTransactionIsolationLevel(default)
            }
        }

        override suspend fun setTransactionIsolationLevel(level: Transaction.IsolationLevel): Result<Unit> {
            return super.setTransactionIsolationLevel(level).also { _transactionIsolationLevel = level }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                sqlx { c -> sqlx4k_cn_query(rt, cn, sql, c, DriverNativeUtils.fn) }.use {
                    it.throwIfError()
                    it.rows_affected.toLong()
                }
            }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                sqlx { c -> sqlx4k_cn_fetch_all(rt, cn, sql, c, DriverNativeUtils.fn) }
                    .use { it.toResultSet() }
                    .toResult()
            }
        }

        override suspend fun begin(): Result<Transaction> = runCatching {
            mutex.withLock {
                assertIsOpen()
                sqlx { c -> sqlx4k_cn_tx_begin(rt, cn, c, DriverNativeUtils.fn) }.use {
                    it.throwIfError()
                    Tx(rt, it.tx!!, encoders)
                }
            }
        }
    }

    class Tx(
        private val rt: CPointer<out CPointed>,
        private var tx: CPointer<out CPointed>,
        override val encoders: Statement.ValueEncoderRegistry
    ) : Transaction {
        private val mutex = Mutex()
        private var _status: Transaction.Status = Transaction.Status.Open
        override val status: Transaction.Status get() = _status

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                assertIsOpen()
                _status = Transaction.Status.Closed
                sqlx { c -> sqlx4k_tx_commit(rt, tx, c, DriverNativeUtils.fn) }.throwIfError()
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                assertIsOpen()
                _status = Transaction.Status.Closed
                sqlx { c -> sqlx4k_tx_rollback(rt, tx, c, DriverNativeUtils.fn) }.throwIfError()
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                sqlx { c -> sqlx4k_tx_query(rt, tx, sql, c, DriverNativeUtils.fn) }.use {
                    tx = it.tx!!
                    it.throwIfError()
                    it.rows_affected.toLong()
                }
            }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                sqlx { c -> sqlx4k_tx_fetch_all(rt, tx, sql, c, DriverNativeUtils.fn) }.use {
                    tx = it.tx!!
                    it.toResultSet()
                }.toResult()
            }
        }
    }
}
