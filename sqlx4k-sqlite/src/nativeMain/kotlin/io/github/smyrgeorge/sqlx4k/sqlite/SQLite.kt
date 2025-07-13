package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.*
import io.github.smyrgeorge.sqlx4k.impl.extensions.*
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sqlx4k.*

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
    options: Driver.Pool.Options = Driver.Pool.Options(),
) : Driver, Driver.Pool, Driver.Transactional, Driver.Migrate {
    init {
        sqlx4k_of(
            url = url,
            username = null,
            password = null,
            min_connections = options.minConnections ?: -1,
            max_connections = options.maxConnections,
            acquire_timeout_milis = options.acquireTimeout?.inWholeMilliseconds?.toInt() ?: -1,
            idle_timeout_milis = options.idleTimeout?.inWholeMilliseconds?.toInt() ?: -1,
            max_lifetime_milis = options.maxLifetime?.inWholeMilliseconds?.toInt() ?: -1,
        ).throwIfError()
    }

    override suspend fun migrate(path: String): Result<Unit> = runCatching {
        sqlx { c -> sqlx4k_migrate(path, c, DriverNativeUtils.fn) }.throwIfError()
    }

    override suspend fun close(): Result<Unit> = runCatching {
        sqlx { c -> sqlx4k_close(c, DriverNativeUtils.fn) }.throwIfError()
    }

    override fun poolSize(): Int = sqlx4k_pool_size()
    override fun poolIdleSize(): Int = sqlx4k_pool_idle_size()

    override suspend fun execute(sql: String): Result<Long> = runCatching {
        sqlx { c -> sqlx4k_query(sql, c, DriverNativeUtils.fn) }.rowsAffectedOrError()
    }

    override suspend fun execute(statement: Statement): Result<Long> =
        execute(statement.render(encoders))

    override suspend fun fetchAll(sql: String): Result<ResultSet> {
        val res = sqlx { c -> sqlx4k_fetch_all(sql, c, DriverNativeUtils.fn) }
        return res.use { it.toResultSet() }.toResult()
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        fetchAll(statement.render(encoders))

    override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
        fetchAll(statement.render(encoders), rowMapper)

    override suspend fun begin(): Result<Transaction> = runCatching {
        sqlx { c -> sqlx4k_tx_begin(c, DriverNativeUtils.fn) }.use {
            it.throwIfError()
            Tx(it.tx!!)
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
        private var tx: CPointer<out CPointed>
    ) : Transaction {
        private val mutex = Mutex()
        private var _status: Transaction.Status = Transaction.Status.Open
        override val status: Transaction.Status = _status

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                isOpenOrError()
                _status = Transaction.Status.Closed
                sqlx { c -> sqlx4k_tx_commit(tx, c, DriverNativeUtils.fn) }.throwIfError()
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                isOpenOrError()
                _status = Transaction.Status.Closed
                sqlx { c -> sqlx4k_tx_rollback(tx, c, DriverNativeUtils.fn) }.throwIfError()
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                isOpenOrError()
                sqlx { c -> sqlx4k_tx_query(tx, sql, c, DriverNativeUtils.fn) }.use {
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
                sqlx { c -> sqlx4k_tx_fetch_all(tx, sql, c, DriverNativeUtils.fn) }.use {
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
         * This registry is utilized in methods like `execute`, `fetchAll`, and other database operation methods to ensure
         * that parameters bound to SQL statements are correctly encoded before being executed.
         */
        val encoders = Statement.ValueEncoderRegistry()
    }
}
