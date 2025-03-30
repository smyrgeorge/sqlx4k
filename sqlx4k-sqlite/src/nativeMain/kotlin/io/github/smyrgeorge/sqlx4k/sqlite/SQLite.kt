package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ResultSetHolder
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sqlx4k.sqlx4k_close
import sqlx4k.sqlx4k_fetch_all
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
@Suppress("unused")
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

    override suspend fun fetchAll(sql: String): Result<ResultSetHolder> {
        val res = sqlx { c -> sqlx4k_fetch_all(sql, c, Driver.fn) }
        return ResultSet(res).toResult()
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSetHolder> =
        fetchAll(statement.render(encoders))

    override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
        fetchAll(statement.render(encoders), rowMapper)

    override suspend fun begin(): Result<Transaction> = runCatching {
        val tx = sqlx { c -> sqlx4k_tx_begin(c, Driver.fn) }.tx()
        Tx(tx.first)
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
                val res = sqlx { c -> sqlx4k_tx_query(tx, sql, c, Driver.fn) }.tx()
                tx = res.first
                res.second.toLong()
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            execute(statement.render(encoders))

        override suspend fun fetchAll(sql: String): Result<ResultSetHolder> {
            val res = mutex.withLock {
                isOpenOrError()
                val r = sqlx { c -> sqlx4k_tx_fetch_all(tx, sql, c, Driver.fn) }
                ResultSet(r)
            }

            tx = res.getRaw().tx!!
            return res.toResult()
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSetHolder> =
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
