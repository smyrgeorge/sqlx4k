package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.DriverNativeUtils
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.rowsAffectedOrError
import io.github.smyrgeorge.sqlx4k.impl.extensions.rtOrError
import io.github.smyrgeorge.sqlx4k.impl.extensions.sqlx
import io.github.smyrgeorge.sqlx4k.impl.extensions.throwIfError
import io.github.smyrgeorge.sqlx4k.impl.extensions.toResultSet
import io.github.smyrgeorge.sqlx4k.impl.extensions.use
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migration
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sqlx4k.sqlx4k_close
import sqlx4k.sqlx4k_cn_acquire
import sqlx4k.sqlx4k_cn_fetch_all
import sqlx4k.sqlx4k_cn_query
import sqlx4k.sqlx4k_cn_release
import sqlx4k.sqlx4k_cn_tx_begin
import sqlx4k.sqlx4k_fetch_all
import sqlx4k.sqlx4k_of
import sqlx4k.sqlx4k_pool_idle_size
import sqlx4k.sqlx4k_pool_size
import sqlx4k.sqlx4k_query
import sqlx4k.sqlx4k_tx_begin
import sqlx4k.sqlx4k_tx_commit
import sqlx4k.sqlx4k_tx_fetch_all
import sqlx4k.sqlx4k_tx_query
import sqlx4k.sqlx4k_tx_rollback
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
 */
@OptIn(ExperimentalForeignApi::class)
class SQLite(
    url: String,
    options: QueryExecutor.Pool.Options = QueryExecutor.Pool.Options(),
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
        afterStatementExecution: suspend (Statement, Duration) -> Unit,
        afterFileMigration: suspend (Migration, Duration) -> Unit
    ): Result<Unit> = Migrator.migrate(
        db = this,
        path = path,
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
     * Represents a native database connection that implements the `Connection` interface.
     *
     * This class encapsulates a low-level, pointer-based interface to interact directly with
     * a database connection and provides methods for executing queries, transactions, and managing
     * the connection lifecycle.
     *
     * @constructor Creates a new instance of the `Cn` class.
     * @property rt A `CPointer` representing the runtime context for the connection.
     *              This pointer is required for any database operations performed through this connection.
     * @property cn A `CPointer` representing the native connection object.
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
                assertIsAcquired()
                _status = Connection.Status.Released
                sqlx { c -> sqlx4k_cn_release(rt, cn, c, DriverNativeUtils.fn) }.throwIfError()
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsAcquired()
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
                assertIsAcquired()
                sqlx { c -> sqlx4k_cn_fetch_all(rt, cn, sql, c, DriverNativeUtils.fn) }
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
                assertIsAcquired()
                sqlx { c -> sqlx4k_cn_tx_begin(rt, cn, c, DriverNativeUtils.fn) }.use {
                    it.throwIfError()
                    Tx(rt, it.tx!!)
                }
            }
        }
    }

    /**
     * Represents a specific implementation of the `Transaction` interface.
     * This class facilitates the management of database transactions, including
     * methods to commit, roll back, execute queries, and fetch results.
     *
     * Transactions in this class are thread-safe, ensuring consistent access
     * to transaction operations through the usage of a [Mutex].
     *
     * This implementation ensures that transactional queries and operations
     * are properly committed or rolled back, and verifies the transactional
     * state before allowing execution.
     *
     * @constructor Creates an instance of the `Tx` class with the given transaction pointer.
     * @param tx A pointer to the transaction object in memory used for transactional operations.
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

        override suspend fun execute(statement: Statement): Result<Long> =
            execute(statement.render(encoders))

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
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
         * The `ValueEncoderRegistry` instance used for encoding values supplied to SQL statements in the `SQLite` class.
         * This registry maps data types to their corresponding encoders, which convert values into a format suitable for
         * inclusion in SQL queries.
         *
         * This registry is used in methods like `execute`, `fetchAll`, and other database operation methods to ensure
         * that parameters bound to SQL statements are correctly encoded before being executed.
         */
        val encoders = Statement.ValueEncoderRegistry()
    }
}
