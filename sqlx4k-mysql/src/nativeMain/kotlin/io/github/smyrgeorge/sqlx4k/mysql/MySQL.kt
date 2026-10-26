@file:Suppress("SqlNoDataSourceInspection")

package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.ExecuteResult
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.Transaction.IsolationLevel
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migration
import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sqlx4k.mysql.Sqlx4kMysqlResult
import sqlx4k.mysql.sqlx4k_mysql_close
import sqlx4k.mysql.sqlx4k_mysql_cn_acquire
import sqlx4k.mysql.sqlx4k_mysql_cn_fetch_all
import sqlx4k.mysql.sqlx4k_mysql_cn_fetch_all_with_params
import sqlx4k.mysql.sqlx4k_mysql_cn_query
import sqlx4k.mysql.sqlx4k_mysql_cn_query_with_params
import sqlx4k.mysql.sqlx4k_mysql_cn_release
import sqlx4k.mysql.sqlx4k_mysql_cn_stream_open
import sqlx4k.mysql.sqlx4k_mysql_cn_tx_begin
import sqlx4k.mysql.sqlx4k_mysql_fetch_all
import sqlx4k.mysql.sqlx4k_mysql_fetch_all_with_params
import sqlx4k.mysql.sqlx4k_mysql_of
import sqlx4k.mysql.sqlx4k_mysql_pool_idle_size
import sqlx4k.mysql.sqlx4k_mysql_pool_size
import sqlx4k.mysql.sqlx4k_mysql_query
import sqlx4k.mysql.sqlx4k_mysql_query_with_params
import sqlx4k.mysql.sqlx4k_mysql_stream_open
import sqlx4k.mysql.sqlx4k_mysql_tx_begin
import sqlx4k.mysql.sqlx4k_mysql_tx_commit
import sqlx4k.mysql.sqlx4k_mysql_tx_fetch_all
import sqlx4k.mysql.sqlx4k_mysql_tx_fetch_all_with_params
import sqlx4k.mysql.sqlx4k_mysql_tx_query
import sqlx4k.mysql.sqlx4k_mysql_tx_query_with_params
import sqlx4k.mysql.sqlx4k_mysql_tx_rollback
import sqlx4k.mysql.sqlx4k_mysql_tx_stream_open
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
    override val encoders: ValueEncoderRegistry = ValueEncoderRegistry()
) : IMySQL {

    private val rt: CPointer<out CPointed> = sqlx4k_mysql_of(
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
        schema = schema,
        createSchema = createSchema,
        dialect = Dialect.MySQL,
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
        schema = schema,
        createSchema = createSchema,
        dialect = Dialect.MySQL,
        afterStatementExecution = afterStatementExecution,
        afterFileMigration = afterFileMigration
    )

    override suspend fun close(): Result<Unit> = runCatching {
        sqlx { c -> sqlx4k_mysql_close(rt, c, fn) }.throwIfError()
    }

    override fun poolSize(): Int = sqlx4k_mysql_pool_size(rt)
    override fun poolIdleSize(): Int = sqlx4k_mysql_pool_idle_size(rt)

    override suspend fun acquire(): Result<Connection> = runCatching {
        sqlx { c -> sqlx4k_mysql_cn_acquire(rt, c, fn) }.use {
            it.throwIfError()
            SqlxConnection(rt, it.cn!!, encoders)
        }
    }

    override suspend fun execute(sql: String): Result<Long> = executeWithResult(sql).map { it.rowsAffected }

    override suspend fun execute(statement: Statement): Result<Long> =
        executeWithResult(statement).map { it.rowsAffected }

    override suspend fun executeWithResult(sql: String): Result<ExecuteResult> = runCatching {
        sqlx { c -> sqlx4k_mysql_query(rt, sql, c, fn) }.executeResultOrError()
    }

    override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> = runCatching {
        statement.withNativeQuery(encoders) { sql, params, paramsLen ->
            sqlx { c -> sqlx4k_mysql_query_with_params(rt, sql, params, paramsLen, c, fn) }.executeResultOrError()
        }
    }

    override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
        sqlx { c -> sqlx4k_mysql_fetch_all(rt, sql, c, fn) }.resultSetOrError()
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> = runCatching {
        statement.withNativeQuery(encoders) { sql, params, paramsLen ->
            sqlx { c -> sqlx4k_mysql_fetch_all_with_params(rt, sql, params, paramsLen, c, fn) }.resultSetOrError()
        }
    }

    override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = streamFlow(rt) {
        sqlx { c -> sqlx4k_mysql_stream_open(rt, sql, null, 0, fetchSize, c, fn) }
    }

    override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> = streamFlow(rt) {
        statement.withNativeQuery(encoders) { sql, params, paramsLen ->
            sqlx { c -> sqlx4k_mysql_stream_open(rt, sql, params, paramsLen, fetchSize, c, fn) }
        }
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        sqlx { c -> sqlx4k_mysql_tx_begin(rt, c, fn) }.use {
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

        // The transactions begun on this connection (a begin() while one is open nests in it, as a savepoint): they
        // share the mutex (all drive the same native connection), and close() rolls back the ones still open.
        private val transactions = mutableListOf<SqlxTransaction>()
        private var _status: Connection.Status = Connection.Status.Open
        override val status: Connection.Status get() = _status
        private var _transactionIsolationLevel: IsolationLevel? = null
        override val transactionIsolationLevel: IsolationLevel? get() = _transactionIsolationLevel

        // Runs to completion also when the caller is cancelled: waiting for the lock (held by a query still running
        // on the connection) would otherwise throw, and the connection would never go back to the pool.
        override suspend fun close(): Result<Unit> = runCatching {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (status == Connection.Status.Closed) return@withLock
                    _status = Connection.Status.Closed
                    // An open transaction would keep the connection inside it once back in the pool, and its native
                    // handle would outlive the connection it borrows: roll them all back first, innermost first.
                    transactions.asReversed().forEach { runCatching { it.rollbackIfOpenLocked() } }
                    transactions.clear()

                    transactionIsolationLevel?.let {
                        val default = IMySQL.DEFAULT_TRANSACTION_ISOLATION_LEVEL
                        setTransactionIsolationLevel(default, false)
                    }

                    sqlx { c -> sqlx4k_mysql_cn_release(rt, cn, c, fn) }.throwIfError()
                }
            }
        }

        private suspend fun setTransactionIsolationLevel(level: IsolationLevel, lock: Boolean): Result<Unit> {
            // language=SQL
            val sql = "SET SESSION TRANSACTION ISOLATION LEVEL ${level.value}"
            return execute(sql, lock).map { }.onSuccess { _transactionIsolationLevel = level }
        }

        override suspend fun setTransactionIsolationLevel(level: IsolationLevel): Result<Unit> =
            setTransactionIsolationLevel(level, true)

        private suspend fun execute(sql: String, lock: Boolean): Result<Long> =
            executeWithResult(sql, lock).map { it.rowsAffected }

        private suspend fun executeWithResult(sql: String, lock: Boolean): Result<ExecuteResult> {
            suspend fun doExecute(): ExecuteResult =
                sqlx { c -> sqlx4k_mysql_cn_query(rt, cn, sql, c, fn) }.executeResultOrError()

            return if (lock) mutex.withLockCatching(::assertIsOpen) { doExecute() } else runCatching { doExecute() }
        }

        override suspend fun execute(sql: String): Result<Long> = execute(sql, true)

        override suspend fun execute(statement: Statement): Result<Long> =
            executeWithResult(statement).map { it.rowsAffected }

        override suspend fun executeWithResult(sql: String): Result<ExecuteResult> = executeWithResult(sql, true)

        override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) {
                statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                    sqlx { c -> sqlx4k_mysql_cn_query_with_params(rt, cn, sql, params, paramsLen, c, fn) }
                        .executeResultOrError()
                }
            }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = mutex.withLockCatching(::assertIsOpen) {
            sqlx { c -> sqlx4k_mysql_cn_fetch_all(rt, cn, sql, c, fn) }.resultSetOrError()
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            mutex.withLockCatching(::assertIsOpen) {
                statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                    sqlx { c -> sqlx4k_mysql_cn_fetch_all_with_params(rt, cn, sql, params, paramsLen, c, fn) }
                        .resultSetOrError()
                }
            }

        // The connection is held (its mutex locked) for the whole collection: a stream keeps the
        // connection busy, exactly as it does in sqlx.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = mutex.flowWithLock(::assertIsOpen) {
            streamFlow(rt) {
                sqlx { c -> sqlx4k_mysql_cn_stream_open(rt, cn, sql, null, 0, fetchSize, c, fn) }
            }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) {
                streamFlow(rt) {
                    statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                        sqlx { c -> sqlx4k_mysql_cn_stream_open(rt, cn, sql, params, paramsLen, fetchSize, c, fn) }
                    }
                }
            }

        override suspend fun begin(): Result<Transaction> = mutex.withLockCatching(::assertIsOpen) {
            sqlx { c -> sqlx4k_mysql_cn_tx_begin(rt, cn, c, fn) }.use {
                it.throwIfError()
                SqlxTransaction(rt, it.tx!!, encoders, mutex).also { tx ->
                    transactions.removeAll { it.status != Transaction.Status.Open }
                    transactions += tx
                }
            }
        }
    }

    class SqlxTransaction internal constructor(
        private val rt: CPointer<out CPointed>,
        private var tx: CPointer<out CPointed>,
        override val encoders: ValueEncoderRegistry,
        // Shared with the connection the transaction was begun on, which drives the same native connection.
        private val mutex: Mutex,
    ) : Transaction {
        constructor(
            rt: CPointer<out CPointed>,
            tx: CPointer<out CPointed>,
            encoders: ValueEncoderRegistry
        ) : this(rt, tx, encoders, Mutex())

        private var _status: Transaction.Status = Transaction.Status.Open
        private var _commited: Boolean = false
        private var _rollbacked: Boolean = false
        override val status: Transaction.Status get() = _status
        override val commited: Boolean get() = _commited
        override val rollbacked: Boolean get() = _rollbacked

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                if (commited) return@withLock
                assertIsOpen()
                _status = Transaction.Status.Closed
                sqlx { c -> sqlx4k_mysql_tx_commit(rt, tx, c, fn) }.throwIfError()
                _commited = true
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                if (rollbacked) return@withLock
                assertIsOpen()
                _status = Transaction.Status.Closed
                sqlx { c -> sqlx4k_mysql_tx_rollback(rt, tx, c, fn) }.throwIfError()
                _rollbacked = true
            }
        }

        /**
         * Rolls the transaction back if it is still open: called by the connection it was begun on when that is
         * closed, with the shared mutex already held.
         */
        internal suspend fun rollbackIfOpenLocked() {
            if (status != Transaction.Status.Open) return
            _status = Transaction.Status.Closed
            sqlx { c -> sqlx4k_mysql_tx_rollback(rt, tx, c, fn) }.throwIfError()
            _rollbacked = true
        }

        override suspend fun execute(sql: String): Result<Long> = executeWithResult(sql).map { it.rowsAffected }

        override suspend fun execute(statement: Statement): Result<Long> =
            executeWithResult(statement).map { it.rowsAffected }

        override suspend fun executeWithResult(sql: String): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) {
                sqlx { c -> sqlx4k_mysql_tx_query(rt, tx, sql, c, fn) }.useTx { it.toExecuteResultOrError() }
            }

        override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) {
                statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                    sqlx { c -> sqlx4k_mysql_tx_query_with_params(rt, tx, sql, params, paramsLen, c, fn) }
                        .useTx { it.toExecuteResultOrError() }
                }
            }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = mutex.withLockCatching(::assertIsOpen) {
            sqlx { c -> sqlx4k_mysql_tx_fetch_all(rt, tx, sql, c, fn) }.useTx { it.toResultSetOrError() }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            mutex.withLockCatching(::assertIsOpen) {
                statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                    sqlx { c -> sqlx4k_mysql_tx_fetch_all_with_params(rt, tx, sql, params, paramsLen, c, fn) }
                        .useTx { it.toResultSetOrError() }
                }
            }

        // The transaction is held (its mutex locked) for the whole collection; the stream borrows the
        // transaction in place, so the `tx` pointer is unchanged afterwards.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = mutex.flowWithLock(::assertIsOpen) {
            streamFlow(rt) {
                sqlx { c -> sqlx4k_mysql_tx_stream_open(rt, tx, sql, null, 0, fetchSize, c, fn) }
            }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) {
                streamFlow(rt) {
                    statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                        sqlx { c -> sqlx4k_mysql_tx_stream_open(rt, tx, sql, params, paramsLen, fetchSize, c, fn) }
                    }
                }
            }

        /**
         * Like [use], also taking back the transaction handle: every query call moves the transaction to the Rust
         * side, which hands it back with the result.
         */
        private inline fun <T> CPointer<Sqlx4kMysqlResult>?.useTx(block: (Sqlx4kMysqlResult) -> T): T = use {
            tx = it.tx!!
            block(it)
        }
    }
}
