@file:OptIn(ExperimentalUuidApi::class)
@file:Suppress("SqlSourceToSinkFlow", "SqlNoDataSourceInspection")

package io.github.smyrgeorge.sqlx4k.sqlite

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.pool.HikariPool
import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.ExecuteResult
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.Transaction.IsolationLevel
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migration
import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import io.github.smyrgeorge.sqlx4k.sqlite.pool.PooledTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import java.sql.Connection as NativeJdbcConnection

/**
 * SQLite class provides mechanisms to interact with a SQLite database on the JVM platform.
 * It implements `Driver`, `Driver.Pool`, and `Driver.Transactional` interfaces,
 * offering functionalities such as connection pooling, executing queries,
 * fetching data, and handling transactions.
 *
 * The URL uses the same sqlx-style format as the native target, so one string works everywhere:
 * - `sqlite::memory:` - Creates an in-memory database
 * - `sqlite:database.db` / `sqlite://database.db` - Creates/opens a database file (relative path)
 * - `sqlite:///path/to/database.db` - Uses an absolute path
 * - `sqlite:///path/to/database.db?mode=rwc` - Query parameters as defined by SQLite URI filenames
 *   (`mode=ro|rw|rwc|memory`, `cache=shared|private`, `immutable`, `vfs`)
 *
 * Any other parameter is rejected, as on the other targets. A `jdbc:sqlite:` prefix is accepted as
 * well and skips that check, so JDBC-style URLs with driver-specific parameters (e.g. xerial pragmas
 * such as `?journal_mode=WAL`) keep working. See [SQLiteUrl] and [toJdbcUrl] for the translation.
 *
 * Connections are pooled with HikariCP, which raises timeouts below its minimums: `acquireTimeout` to 250 ms,
 * `idleTimeout` to 10 seconds and `maxLifetime` to 30 seconds. The database is opened when the instance is
 * created, so a database that cannot be opened fails right away.
 *
 * @param url The URL of the SQLite database to connect to.
 * @param options Optional pool configuration, defaulting to `ConnectionPool.Options`.
 * @param encoders Optional registry of value encoders to use for encoding query parameters.
 */
class SQLite(
    url: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
    override val encoders: ValueEncoderRegistry = ValueEncoderRegistry()
) : ISQLite {
    private val dataSource: HikariDataSource = createDataSource(url, options)

    // One permit per connection, taken before blocking an IO thread in HikariCP (and given back when the connection
    // is closed). Callers beyond the pool size wait here, suspended: blocked in HikariCP, enough of them would take
    // up every IO thread, and the connections' holders could no longer run a statement to give them back.
    private val permits = Semaphore(options.maxConnections)
    private val acquireTimeout: Duration? = options.acquireTimeout

    // Whether the permit was taken is recorded in the block: withTimeoutOrNull returns null when the timeout fires
    // after acquire() returned, and a permit taken but then reported as timed out (or cancelled) would never be given
    // back, the pool losing one of its connections for good.
    private suspend fun takePermit() {
        val timeout = acquireTimeout ?: return permits.acquire()
        var taken = false
        try {
            withTimeoutOrNull(timeout) {
                permits.acquire()
                taken = true
            }
        } catch (e: CancellationException) {
            if (taken) permits.release()
            throw e
        }
        if (!taken) SQLError(SQLError.Code.PoolTimedOut, "Timed out waiting for a connection").raise()
    }

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
        withContext(Dispatchers.IO) { dataSource.close() }
    }

    override fun poolSize(): Int = dataSource.hikariPoolMXBean?.totalConnections ?: 0
    override fun poolIdleSize(): Int = dataSource.hikariPoolMXBean?.idleConnections ?: 0

    override suspend fun acquire(): Result<Connection> = runCatching {
        if (dataSource.isClosed) SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").raise()
        takePermit()

        // Taken on an IO thread, interruptibly, so a cancelled caller stops waiting for the pool. A connection
        // handed over just as the caller is cancelled goes straight back to the pool instead of leaking.
        val taken = AtomicReference<NativeJdbcConnection?>(null)
        val connection = try {
            runInterruptible(Dispatchers.IO) { dataSource.connection.also { taken.set(it) } }
        } catch (e: CancellationException) {
            taken.get()?.close()
            permits.release()
            throw e
        } catch (e: SQLException) {
            permits.release()
            when {
                dataSource.isClosed -> SQLError(SQLError.Code.PoolClosed, "Connection pool is closed", e).raise()
                e is SQLTransientConnectionException -> SQLError(SQLError.Code.PoolTimedOut, e.message, e).raise()
                else -> e.toSQLError().raise()
            }
        }
        JdbcConnection(connection, encoders, onClose = permits::release)
    }

    override suspend fun execute(sql: String): Result<Long> = executeWithResult(sql).map { it.rowsAffected }

    override suspend fun executeWithResult(sql: String): Result<ExecuteResult> =
        withConnection { executeWithResult(sql).getOrThrow() }

    override suspend fun fetchAll(sql: String): Result<ResultSet> =
        withConnection { fetchAll(sql).getOrThrow() }

    override suspend fun execute(statement: Statement): Result<Long> =
        executeWithResult(statement).map { it.rowsAffected }

    override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> =
        withConnection { executeWithResult(statement).getOrThrow() }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        withConnection { fetchAll(statement).getOrThrow() }

    override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> =
        flowWithConnection { fetch(sql, fetchSize) }

    override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
        flowWithConnection { fetch(statement, fetchSize) }

    override suspend fun begin(): Result<Transaction> = runCatching {
        val connection = acquire().getOrThrow()
        try {
            val tx = connection.begin().getOrThrow()
            // Wrap the transaction to ensure the pooled connection is released
            PooledTransaction(tx, connection)
        } catch (e: Exception) {
            connection.close()
            e.toSQLError().raise()
        }
    }

    class JdbcConnection(
        private val connection: NativeJdbcConnection,
        override val encoders: ValueEncoderRegistry,
        // Gives the pool permit back, once the connection is closed.
        private val onClose: () -> Unit = {},
    ) : Connection {
        private val mutex = Mutex()
        private var _status: Connection.Status = Connection.Status.Open
        override val status: Connection.Status get() = _status
        override val transactionIsolationLevel: IsolationLevel? = null

        // Runs to completion also when the caller is cancelled: withContext(Dispatchers.IO) would otherwise throw on
        // entry, after the status was set to Closed, and the connection would never go back to the pool.
        override suspend fun close(): Result<Unit> = runCatching {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (status == Connection.Status.Closed) return@withLock
                    _status = Connection.Status.Closed
                    try {
                        withContext(Dispatchers.IO) {
                            connection.close()
                        }
                    } finally {
                        onClose()
                    }
                }
            }
        }

        override suspend fun setTransactionIsolationLevel(level: IsolationLevel): Result<Unit> {
            // SQLite does not support setting the transaction isolation level.
            return Result.success(Unit)
        }

        override suspend fun execute(sql: String): Result<Long> = executeWithResult(sql).map { it.rowsAffected }

        override suspend fun executeWithResult(sql: String): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) {
                withContext(Dispatchers.IO) {
                    connection.executeForResult(sql) {
                        connection.createStatement().use { statement -> statement.executeUpdate(sql) }
                    }
                }
            }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = mutex.withLockCatching(::assertIsOpen) {
            withContext(Dispatchers.IO) {
                connection.createStatement().use { stmt -> stmt.executeQuery(sql).toResultSet() }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            executeWithResult(statement).map { it.rowsAffected }

        override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) {
                withContext(Dispatchers.IO) {
                    connection.executeForResult(statement.sql) {
                        connection.prepareStatement(statement, encoders).use { stmt -> stmt.executeUpdate() }
                    }
                }
            }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            mutex.withLockCatching(::assertIsOpen) {
                withContext(Dispatchers.IO) {
                    connection.prepareStatement(statement, encoders).use { stmt -> stmt.executeQuery().toResultSet() }
                }
            }

        // The connection is held (its mutex locked) for the whole collection.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = mutex.flowWithLock(::assertIsOpen) {
            jdbcRows(fetchSize) {
                val stmt = connection.createStatement()
                stmt to stmt.executeQuery(sql)
            }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) {
                jdbcRows(fetchSize) {
                    val stmt = connection.prepareStatement(statement, encoders)
                    stmt to stmt.executeQuery()
                }
            }

        // The last transaction begun on this connection: a begin() nests in it (or in the one it is nested in) while
        // it is open.
        private var transaction: JdbcTransaction? = null

        // A begin() while a transaction is open nests in it, as a savepoint, as on the native driver: turning
        // auto-commit off again did nothing, so the nested one's commit committed the whole of it, and the outer one's
        // rollback then failed, auto-commit being on again.
        override suspend fun begin(): Result<Transaction> = mutex.withLockCatching(::assertIsOpen) {
            // Begun to completion also when the caller is cancelled meanwhile: withContext(Dispatchers.IO) would
            // otherwise discard the transaction on return, left open on the connection, and recorded as the one a
            // later begin() nests in (whose commit then only released a savepoint).
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    var parent = transaction
                    while (parent != null && parent.status != Transaction.Status.Open) parent = parent.parent
                    if (parent == null) {
                        connection.autoCommit = false
                        JdbcTransaction(connection, false, encoders)
                    } else {
                        val savepoint = rethrowAsSQLError {
                            connection.setSavepoint("sqlx4k_savepoint_${parent.depth}")
                        }
                        JdbcTransaction(connection, false, encoders, savepoint, parent).also { parent.child = it }
                    }.also { transaction = it }
                }
            }
        }.mapCatching { tx ->
            // A caller cancelled meanwhile never receives it: it is rolled back.
            if (!currentCoroutineContext().isActive) {
                tx.rollback()
                currentCoroutineContext().ensureActive()
            }
            tx
        }
    }

    class JdbcTransaction(
        private var connection: NativeJdbcConnection,
        private val closeConnectionAfterTx: Boolean,
        override val encoders: ValueEncoderRegistry,
        // For a transaction nested in another one (begun on a connection in a transaction): its savepoint, and the
        // transaction it is nested in.
        private val savepoint: java.sql.Savepoint? = null,
        internal val parent: JdbcTransaction? = null,
    ) : Transaction {
        private val mutex = Mutex()

        // The transaction nested in this one, if any.
        internal var child: JdbcTransaction? = null
        internal val depth: Int get() = (parent?.depth ?: 0) + 1
        private var _status: Transaction.Status = Transaction.Status.Open
        private var _commited: Boolean = false
        private var _rollbacked: Boolean = false
        override val status: Transaction.Status get() = _status
        override val commited: Boolean get() = _commited
        override val rollbacked: Boolean get() = _rollbacked

        // Runs to completion also when the caller is cancelled (e.g. from the finally block of a cancelled scope):
        // withContext(Dispatchers.IO) would otherwise throw on entry, after the transaction was marked closed, and the
        // COMMIT would silently never run.
        override suspend fun commit(): Result<Unit> = runCatching {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (commited) return@withLock
                    assertIsOpen()
                    // Its work is part of this one's: ending this one first would leave it (its savepoint) dangling,
                    // and left open, this one held its locks until the connection closed: both are rolled back.
                    if (child?.status == Transaction.Status.Open) {
                        rollbackLocked()
                        SQLError(
                            SQLError.Code.Database,
                            "The transaction was rolled back: one nested in it was still open when it was committed."
                        ).raise()
                    }
                    _status = Transaction.Status.Closed
                    if (savepoint != null) {
                        withContext(Dispatchers.IO) { rethrowAsSQLError { connection.releaseSavepoint(savepoint) } }
                        _commited = true
                        return@withLock
                    }
                    withContext(Dispatchers.IO) {
                        connection.endTransaction(closeConnectionAfterTx) {
                            try {
                                commit()
                            } catch (e: Throwable) {
                                // SQLite keeps the transaction open when the COMMIT fails (a deferred foreign key
                                // violated, a busy database): roll it back, as the native driver does, or the next
                                // statements on the connection would run in it.
                                runCatching { rollback() }.onFailure(e::addSuppressed)
                                runCatching { autoCommit = true }.onFailure(e::addSuppressed)
                                throw e
                            }
                            autoCommit = true
                            _commited = true
                        }
                    }
                }
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (rollbacked) return@withLock
                    assertIsOpen()
                    rollbackLocked()
                }
            }
        }

        /** Rolls the transaction back, the one nested in it first; its lock is held. */
        private suspend fun rollbackLocked() {
            // Rolled back with this one.
            child?.let { if (it.status == Transaction.Status.Open) it.rollback().getOrThrow() }
            _status = Transaction.Status.Closed
            if (savepoint != null) {
                withContext(Dispatchers.IO) {
                    rethrowAsSQLError {
                        connection.rollback(savepoint)
                        connection.releaseSavepoint(savepoint)
                    }
                }
                _rollbacked = true
                return
            }
            withContext(Dispatchers.IO) {
                connection.endTransaction(closeConnectionAfterTx) {
                    rollback()
                    autoCommit = true
                    _rollbacked = true
                }
            }
        }

        override suspend fun execute(sql: String): Result<Long> = executeWithResult(sql).map { it.rowsAffected }

        override suspend fun executeWithResult(sql: String): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) {
                withContext(Dispatchers.IO) {
                    connection.executeForResult(sql) {
                        connection.createStatement().use { statement -> statement.executeUpdate(sql) }
                    }
                }
            }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = mutex.withLockCatching(::assertIsOpen) {
            withContext(Dispatchers.IO) {
                connection.createStatement().use { stmt -> stmt.executeQuery(sql).toResultSet() }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            executeWithResult(statement).map { it.rowsAffected }

        override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) {
                withContext(Dispatchers.IO) {
                    connection.executeForResult(statement.sql) {
                        connection.prepareStatement(statement, encoders).use { stmt -> stmt.executeUpdate() }
                    }
                }
            }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            mutex.withLockCatching(::assertIsOpen) {
                withContext(Dispatchers.IO) {
                    connection.prepareStatement(statement, encoders).use { stmt -> stmt.executeQuery().toResultSet() }
                }
            }

        // The transaction is held (its mutex locked) for the whole collection.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = mutex.flowWithLock(::assertIsOpen) {
            jdbcRows(fetchSize) {
                val stmt = connection.createStatement()
                stmt to stmt.executeQuery(sql)
            }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) {
                jdbcRows(fetchSize) {
                    val stmt = connection.prepareStatement(statement, encoders)
                    stmt to stmt.executeQuery()
                }
            }
    }

    companion object {
        private const val JDBC_PREFIX = "jdbc:"
        internal fun SQLiteUrl.toJdbcUrl(): String = when {
            query == null -> "jdbc:sqlite:$database"
            database.startsWith(SQLiteUrl.FILE_PREFIX) -> "jdbc:sqlite:$database?$query"
            else -> "jdbc:sqlite:${SQLiteUrl.FILE_PREFIX}$database?$query"
        }

        private fun createDataSource(url: String, options: ConnectionPool.Options): HikariDataSource {
            // Accept both the sqlx-style URL shared with the other targets and a JDBC-style one. An
            // sqlx-style URL is validated exactly like on native and Android (only the parameters
            // SQLite defines are allowed); a `jdbc:` URL is the escape hatch for driver-specific
            // parameters such as xerial pragmas (`jdbc:sqlite:data.db?journal_mode=WAL`).
            val isJdbc = url.startsWith(JDBC_PREFIX)
            val parsed = SQLiteUrl.parse(url.removePrefix(JDBC_PREFIX))
            if (!isJdbc) parsed.requireKnownParams()

            // Validate in-memory database configuration
            // In-memory SQLite databases are isolated per connection, so pool size must be 1
            val isInMemory = parsed.isInMemory
            if (isInMemory && options.maxConnections > 1) {
                throw IllegalArgumentException(
                    "SQLite in-memory databases cannot be used with connection pools larger than 1. " +
                            "Each connection creates a separate in-memory database instance. " +
                            "Please set ConnectionPool.Options(minConnections = 1, maxConnections = 1) for in-memory databases."
                )
            }

            // The pool is HikariCP. A missing option disables the matching HikariCP setting (0), so connections
            // are kept and waited for indefinitely, as on the other targets; an in-memory database lives only as
            // long as its connection. HikariCP enforces minimums on the others, so shorter values are raised to
            // them (below its minimum it would otherwise fall back to its 30-minute default lifetime).
            val config = HikariConfig().apply {
                jdbcUrl = parsed.toJdbcUrl()
                poolName = "sqlx4k-sqlite"
                maximumPoolSize = options.maxConnections
                minimumIdle = options.minConnections ?: 0
                connectionTimeout = options.acquireTimeout?.inWholeMilliseconds?.coerceAtLeast(250) ?: 0
                idleTimeout = options.idleTimeout?.inWholeMilliseconds?.coerceAtLeast(10_000) ?: 0
                maxLifetime = options.maxLifetime?.inWholeMilliseconds?.coerceAtLeast(30_000) ?: 0
                isAutoCommit = true
                // Enable WAL mode for file-based databases to improve concurrency. In-memory databases
                // don't support WAL mode, and switching the journal mode writes to the database header,
                // which a read-only (`mode=ro`) connection cannot do.
                if (!isInMemory && !parsed.isReadOnly) connectionInitSql = "PRAGMA journal_mode=WAL"
                // Enforce foreign keys, as the other targets do (SQLite leaves them off by default): a `jdbc:` URL's
                // own `foreign_keys` parameter takes precedence.
                addDataSourceProperty("foreign_keys", "true")
            }

            // HikariCP opens a first connection here, so a database that cannot be opened fails right away
            // instead of every acquire waiting for its timeout.
            return try {
                HikariDataSource(config)
            } catch (e: HikariPool.PoolInitializationException) {
                (e.cause ?: e).toSQLError(SQLError.Code.Pool).raise()
            }
        }
    }
}
