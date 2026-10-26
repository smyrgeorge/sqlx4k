package io.github.smyrgeorge.sqlx4k.sqlite

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
import sqlx4k.sqlite.Sqlx4kSqliteResult
import sqlx4k.sqlite.sqlx4k_sqlite_close
import sqlx4k.sqlite.sqlx4k_sqlite_cn_acquire
import sqlx4k.sqlite.sqlx4k_sqlite_cn_fetch_all
import sqlx4k.sqlite.sqlx4k_sqlite_cn_fetch_all_with_params
import sqlx4k.sqlite.sqlx4k_sqlite_cn_query
import sqlx4k.sqlite.sqlx4k_sqlite_cn_query_with_params
import sqlx4k.sqlite.sqlx4k_sqlite_cn_release
import sqlx4k.sqlite.sqlx4k_sqlite_cn_stream_open
import sqlx4k.sqlite.sqlx4k_sqlite_cn_tx_begin
import sqlx4k.sqlite.sqlx4k_sqlite_fetch_all
import sqlx4k.sqlite.sqlx4k_sqlite_fetch_all_with_params
import sqlx4k.sqlite.sqlx4k_sqlite_of
import sqlx4k.sqlite.sqlx4k_sqlite_pool_idle_size
import sqlx4k.sqlite.sqlx4k_sqlite_pool_size
import sqlx4k.sqlite.sqlx4k_sqlite_query
import sqlx4k.sqlite.sqlx4k_sqlite_query_with_params
import sqlx4k.sqlite.sqlx4k_sqlite_stream_open
import sqlx4k.sqlite.sqlx4k_sqlite_tx_begin
import sqlx4k.sqlite.sqlx4k_sqlite_tx_commit
import sqlx4k.sqlite.sqlx4k_sqlite_tx_fetch_all
import sqlx4k.sqlite.sqlx4k_sqlite_tx_fetch_all_with_params
import sqlx4k.sqlite.sqlx4k_sqlite_tx_query
import sqlx4k.sqlite.sqlx4k_sqlite_tx_query_with_params
import sqlx4k.sqlite.sqlx4k_sqlite_tx_rollback
import sqlx4k.sqlite.sqlx4k_sqlite_tx_stream_open
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
    private val rt: CPointer<out CPointed> = sqlx4k_sqlite_of(
        url = url,
        username = null,
        password = null,
        min_connections = options.minConnections ?: -1,
        max_connections = options.maxConnections,
        // Capped to the Int the native side takes (about 24.8 days): a longer one wrapped around, to a negative
        // number read there as unset (sqlx's default, e.g. a 30-second acquire timeout) or to a much shorter one.
        acquire_timeout_milis = options.acquireTimeout?.inWholeMilliseconds?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: -1,
        idle_timeout_milis = options.idleTimeout?.inWholeMilliseconds?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: -1,
        max_lifetime_milis = options.maxLifetime?.inWholeMilliseconds?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: -1,
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
        sqlx { c -> sqlx4k_sqlite_close(rt, c, fn) }.throwIfError()
    }

    override fun poolSize(): Int = sqlx4k_sqlite_pool_size(rt)
    override fun poolIdleSize(): Int = sqlx4k_sqlite_pool_idle_size(rt)

    override suspend fun acquire(): Result<Connection> = runCatching {
        sqlx { c -> sqlx4k_sqlite_cn_acquire(rt, c, fn) }.use {
            it.throwIfError()
            SqlxConnection(rt, it.cn!!, encoders)
        }
    }

    override suspend fun execute(sql: String): Result<Long> = executeWithResult(sql).map { it.rowsAffected }

    override suspend fun execute(statement: Statement): Result<Long> =
        executeWithResult(statement).map { it.rowsAffected }

    override suspend fun executeWithResult(sql: String): Result<ExecuteResult> = runCatching {
        sqlx { c -> sqlx4k_sqlite_query(rt, sql, c, fn) }.executeResultOrError(sql)
    }

    override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> = runCatching {
        statement.withNativeQuery(encoders) { sql, params, paramsLen ->
            sqlx { c -> sqlx4k_sqlite_query_with_params(rt, sql, params, paramsLen, c, fn) }
                .executeResultOrError(sql)
        }
    }

    override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
        sqlx { c -> sqlx4k_sqlite_fetch_all(rt, sql, c, fn) }.resultSetOrError()
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> = runCatching {
        statement.withNativeQuery(encoders) { sql, params, paramsLen ->
            sqlx { c -> sqlx4k_sqlite_fetch_all_with_params(rt, sql, params, paramsLen, c, fn) }
                .resultSetOrError()
        }
    }

    override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = streamFlow(rt) {
        sqlx { c -> sqlx4k_sqlite_stream_open(rt, sql, null, 0, fetchSize, c, fn) }
    }

    override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> = streamFlow(rt) {
        statement.withNativeQuery(encoders) { sql, params, paramsLen ->
            sqlx { c -> sqlx4k_sqlite_stream_open(rt, sql, params, paramsLen, fetchSize, c, fn) }
        }
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        sqlx { c -> sqlx4k_sqlite_tx_begin(rt, c, fn) }.use {
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
        override val transactionIsolationLevel: IsolationLevel? = null

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
                    sqlx { c -> sqlx4k_sqlite_cn_release(rt, cn, c, fn) }.throwIfError()
                }
            }
        }

        override suspend fun setTransactionIsolationLevel(level: IsolationLevel): Result<Unit> {
            // SQLite does not support setting the transaction isolation level.
            return Result.success(Unit)
        }

        override suspend fun execute(sql: String): Result<Long> = executeWithResult(sql).map { it.rowsAffected }

        override suspend fun execute(statement: Statement): Result<Long> =
            executeWithResult(statement).map { it.rowsAffected }

        override suspend fun executeWithResult(sql: String): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) {
                sqlx { c -> sqlx4k_sqlite_cn_query(rt, cn, sql, c, fn) }.executeResultOrError(sql)
            }

        override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) {
                statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                    sqlx { c -> sqlx4k_sqlite_cn_query_with_params(rt, cn, sql, params, paramsLen, c, fn) }
                        .executeResultOrError(sql)
                }
            }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = mutex.withLockCatching(::assertIsOpen) {
            sqlx { c -> sqlx4k_sqlite_cn_fetch_all(rt, cn, sql, c, fn) }.resultSetOrError()
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            mutex.withLockCatching(::assertIsOpen) {
                statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                    sqlx { c -> sqlx4k_sqlite_cn_fetch_all_with_params(rt, cn, sql, params, paramsLen, c, fn) }
                        .resultSetOrError()
                }
            }

        // The connection is held (its mutex locked) for the whole collection: a stream keeps the
        // connection busy, exactly as it does in sqlx.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = mutex.flowWithLock(::assertIsOpen) {
            streamFlow(rt) {
                sqlx { c -> sqlx4k_sqlite_cn_stream_open(rt, cn, sql, null, 0, fetchSize, c, fn) }
            }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) {
                streamFlow(rt) {
                    statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                        sqlx { c ->
                            sqlx4k_sqlite_cn_stream_open(rt, cn, sql, params, paramsLen, fetchSize, c, fn)
                        }
                    }
                }
            }

        override suspend fun begin(): Result<Transaction> = mutex.withLockCatching(::assertIsOpen) {
            sqlx { c -> sqlx4k_sqlite_cn_tx_begin(rt, cn, c, fn) }.use {
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
                sqlx { c -> sqlx4k_sqlite_tx_commit(rt, tx, c, fn) }.throwIfError()
                _commited = true
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                if (rollbacked) return@withLock
                assertIsOpen()
                _status = Transaction.Status.Closed
                sqlx { c -> sqlx4k_sqlite_tx_rollback(rt, tx, c, fn) }.throwIfError()
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
            sqlx { c -> sqlx4k_sqlite_tx_rollback(rt, tx, c, fn) }.throwIfError()
            _rollbacked = true
        }

        override suspend fun execute(sql: String): Result<Long> = executeWithResult(sql).map { it.rowsAffected }

        override suspend fun execute(statement: Statement): Result<Long> =
            executeWithResult(statement).map { it.rowsAffected }

        override suspend fun executeWithResult(sql: String): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) {
                sqlx { c -> sqlx4k_sqlite_tx_query(rt, tx, sql, c, fn) }.useTx { it.toExecuteResultOrError(sql) }
            }

        override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) {
                statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                    sqlx { c -> sqlx4k_sqlite_tx_query_with_params(rt, tx, sql, params, paramsLen, c, fn) }
                        .useTx { it.toExecuteResultOrError(sql) }
                }
            }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = mutex.withLockCatching(::assertIsOpen) {
            sqlx { c -> sqlx4k_sqlite_tx_fetch_all(rt, tx, sql, c, fn) }.useTx { it.toResultSetOrError() }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            mutex.withLockCatching(::assertIsOpen) {
                statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                    sqlx { c -> sqlx4k_sqlite_tx_fetch_all_with_params(rt, tx, sql, params, paramsLen, c, fn) }
                        .useTx { it.toResultSetOrError() }
                }
            }

        // The transaction is held (its mutex locked) for the whole collection; the stream borrows the
        // transaction in place, so the `tx` pointer is unchanged afterwards.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = mutex.flowWithLock(::assertIsOpen) {
            streamFlow(rt) {
                sqlx { c -> sqlx4k_sqlite_tx_stream_open(rt, tx, sql, null, 0, fetchSize, c, fn) }
            }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) {
                streamFlow(rt) {
                    statement.withNativeQuery(encoders) { sql, params, paramsLen ->
                        sqlx { c ->
                            sqlx4k_sqlite_tx_stream_open(rt, tx, sql, params, paramsLen, fetchSize, c, fn)
                        }
                    }
                }
            }

        /**
         * Like [use], also taking back the transaction handle: every query call moves the transaction to the Rust
         * side, which hands it back with the result.
         */
        private inline fun <T> CPointer<Sqlx4kSqliteResult>?.useTx(block: (Sqlx4kSqliteResult) -> T): T = use {
            tx = it.tx!!
            block(it)
        }
    }
}
