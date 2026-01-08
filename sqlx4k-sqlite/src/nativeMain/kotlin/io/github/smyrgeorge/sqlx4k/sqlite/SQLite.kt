package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.*
import io.github.smyrgeorge.sqlx4k.Transaction.IsolationLevel
import io.github.smyrgeorge.sqlx4k.impl.extensions.*
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migration
import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sqlx4k.*
import kotlin.time.Duration

/**
 * A database driver for SQLite, implemented with connection pooling and transactional support.
 * This class provides mechanisms to execute SQL queries, manage database connections, and
 * handle transactions in a coroutine-based environment.
 *
 * `sqlite::memory:` | Open an in-memory database.
 * `sqlite:data.db` | Open the file `data.db` in the current directory.
 * `sqlite://data.db` | Open the file `data.db` in the current directory.
 * `sqlite:///data.db` | Open the file `data.db` from the root (`/`) directory.
 * `sqlite://data.db?mode=ro` | Open the file `data.db` for read-only access.
 *
 * @param url The URL string for connecting to the SQLite database.
 * @param options Configuration options for the connection pool, such as minimum and
 * maximum connections, timeout durations, etc.
 * @param encoders Optional registry of value encoders to use for encoding query parameters.
 */
@OptIn(ExperimentalForeignApi::class)
class SQLite(
    url: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
    override val encoders: ValueEncoderRegistry = ValueEncoderRegistry()
) : ISQLite {
    private val rt: CPointer<out CPointed> = sqlx4k_of(
        url = url,
        username = null,
        password = null,
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
        schema = null, // SQLite does not support schemas.
        createSchema = false, // SQLite does not support schemas.
        dialect = Dialect.SQLite,
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
        schema = null, // SQLite does not support schemas.
        createSchema = false, // SQLite does not support schemas.
        dialect = Dialect.SQLite,
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
        schema = null, // SQLite does not support schemas.
        createSchema = false, // SQLite does not support schemas.
        dialect = Dialect.SQLite,
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
            SqlxConnection(rt, it.cn!!, encoders)
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
            SqlxTransaction(rt, it.tx!!, encoders)
        }
    }

    class SqlxConnection(
        private val rt: CPointer<out CPointed>,
        private val cn: CPointer<out CPointed>,
        override val encoders: ValueEncoderRegistry
    ) : Connection {
        private val mutex = Mutex()
        private var _status: Connection.Status = Connection.Status.Open
        override val status: Connection.Status get() = _status
        override val transactionIsolationLevel: IsolationLevel? = null

        override suspend fun close(): Result<Unit> = runCatching {
            mutex.withLock {
                assertIsOpen()
                _status = Connection.Status.Closed
                sqlx { c -> sqlx4k_cn_release(rt, cn, c, DriverNativeUtils.fn) }.throwIfError()
            }
        }

        override suspend fun setTransactionIsolationLevel(level: IsolationLevel): Result<Unit> {
            // SQLite does not support setting the transaction isolation level.
            return Result.success(Unit)
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
                    SqlxTransaction(rt, it.tx!!, encoders)
                }
            }
        }
    }

    class SqlxTransaction(
        private val rt: CPointer<out CPointed>,
        private var tx: CPointer<out CPointed>,
        override val encoders: ValueEncoderRegistry
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
