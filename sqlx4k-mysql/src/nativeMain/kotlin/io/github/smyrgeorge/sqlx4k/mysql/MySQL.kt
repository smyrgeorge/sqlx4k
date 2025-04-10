package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.*
import io.github.smyrgeorge.sqlx4k.impl.extensions.*
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sqlx4k.*

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
 */
@OptIn(ExperimentalForeignApi::class)
class MySQL(
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

    /**
     * Implementation of the `Transaction` interface that provides methods to manage
     * and execute transactional operations using SQL commands. Transactions are
     * synchronized using a `Mutex` to maintain thread safety.
     *
     * @constructor Initializes the transaction implementation with the provided transaction pointer.
     * @property tx The transaction pointer representing the current state and context of the transaction.
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

    companion object {
        /**
         * The `ValueEncoderRegistry` instance used for encoding values supplied to SQL statements in the `MySQL` class.
         * This registry maps data types to their corresponding encoders, which convert values into a format suitable for
         * inclusion in SQL queries.
         *
         * This registry is utilized in methods like `execute`, `fetchAll`, and other database operation methods to ensure
         * that parameters bound to SQL statements are correctly encoded before being executed.
         */
        val encoders = Statement.ValueEncoderRegistry()
    }
}
