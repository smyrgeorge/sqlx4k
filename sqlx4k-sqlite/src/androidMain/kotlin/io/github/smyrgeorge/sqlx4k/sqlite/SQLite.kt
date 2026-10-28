@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
@file:Suppress("SqlSourceToSinkFlow", "DuplicatedCode")

package io.github.smyrgeorge.sqlx4k.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import io.github.smyrgeorge.sqlx4k.sqlite.pool.ConnectionPoolImpl
import io.github.smyrgeorge.sqlx4k.sqlite.pool.PooledTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi

/**
 * SQLite class provides mechanisms to interact with a SQLite database on the Android platform.
 * It implements `Driver`, `Driver.Pool`, and `Driver.Transactional` interfaces,
 * offering functionalities such as connection pooling, executing queries,
 * fetching data, and handling transactions.
 *
 * The URL uses the same sqlx-style format as the other targets:
 * - `sqlite::memory:` - Creates an in-memory database
 * - `sqlite:database.db` / `sqlite://database.db` - Creates/opens a database file in the app's
 *   databases directory (see [Context.getDatabasePath])
 * - `sqlite:///path/to/database.db` - Uses an absolute path
 * - `sqlite:///path/to/database.db?mode=ro` - Opens read-only. `mode` may be `ro`, `rw` (never
 *   creates), `rwc` or `memory`; without it the file is created when missing. `cache` is accepted
 *   but ignored: Android manages its own cache, and in-memory pools are limited to one connection
 *   anyway. Any other parameter (`immutable`, `vfs`, ...) cannot be honoured on Android and is rejected.
 *
 * If the URL does not start with "sqlite:", it will be automatically prefixed.
 *
 * @param context The Android Context to use for database operations.
 * @param url The URL of the SQLite database to connect to.
 * @param options Optional pool configuration, defaulting to `ConnectionPool.Options`.
 * @param encoders Optional registry of value encoders to use for encoding query parameters.
 */
class SQLite(
    private val context: Context,
    url: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
    override val encoders: ValueEncoderRegistry = ValueEncoderRegistry()
) : ISQLite {
    private val pool: ConnectionPoolImpl = createConnectionPool(context, url, options, encoders)

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

    override suspend fun close(): Result<Unit> = pool.close()

    override fun poolSize(): Int = pool.poolSize()
    override fun poolIdleSize(): Int = pool.poolIdleSize()

    override suspend fun acquire(): Result<Connection> = pool.acquire()

    override suspend fun execute(sql: String): Result<Long> =
        pool.withConnection { execute(sql).getOrThrow() }

    override suspend fun executeWithResult(sql: String): Result<ExecuteResult> =
        pool.withConnection { executeWithResult(sql).getOrThrow() }

    override suspend fun fetchAll(sql: String): Result<ResultSet> =
        pool.withConnection { fetchAll(sql).getOrThrow() }

    override suspend fun execute(statement: Statement): Result<Long> =
        pool.withConnection { execute(statement).getOrThrow() }

    override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> =
        pool.withConnection { executeWithResult(statement).getOrThrow() }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        pool.withConnection { fetchAll(statement).getOrThrow() }

    override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> =
        pool.flowWithConnection { fetch(sql, fetchSize) }

    override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
        pool.flowWithConnection { fetch(statement, fetchSize) }

    override suspend fun begin(): Result<Transaction> = runCatching {
        val connection = pool.acquire().getOrThrow()
        try {
            val tx = connection.begin().getOrThrow()
            // Wrap the transaction to ensure the pooled connection is released
            PooledTransaction(tx, connection)
        } catch (e: Exception) {
            connection.close()
            e.toSQLError().raise()
        }
    }

    class AndroidConnection(
        private val db: SQLiteDatabase,
        override val encoders: ValueEncoderRegistry
    ) : Connection {
        private val mutex = Mutex()
        private var _status: Connection.Status = Connection.Status.Open
        override val status: Connection.Status get() = _status
        override val transactionIsolationLevel: IsolationLevel? = null

        // Android's SQLiteDatabase.beginTransaction() stores its session in a ThreadLocal.
        // All operations on this connection must run on the same thread to keep transaction
        // state consistent across coroutine suspend points.
        private val executor = Executors.newSingleThreadExecutor()
        private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

        // The transactions begun on this connection (a begin() while one is open nests in it): see
        // rollbackOpenTransactions.
        private val transactions = mutableListOf<AndroidTransaction>()

        /**
         * Rolls back the transactions begun on this connection that were never ended, innermost first, so the
         * connection does not go back to the pool inside one: the next borrower's statements would run in that
         * transaction (on the same thread) and never be committed, and its write lock would stay held.
         */
        internal suspend fun rollbackOpenTransactions() {
            val open = transactions.asReversed().filter { it.status == Transaction.Status.Open }
            transactions.clear()
            open.forEach { it.rollback() }
        }

        // Runs to completion also when the caller is cancelled: withContext(dispatcher) would otherwise throw on
        // entry and the database would never be closed.
        override suspend fun close(): Result<Unit> = runCatching {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (status == Connection.Status.Closed) return@withLock
                    _status = Connection.Status.Closed
                    try {
                        withContext(dispatcher) {
                            db.close()
                        }
                    } finally {
                        // Always release the dedicated single-thread executor, even if db.close() fails,
                        // otherwise its thread would leak for the lifetime of the process.
                        executor.shutdown()
                    }
                }
            }
        }

        override suspend fun setTransactionIsolationLevel(level: IsolationLevel): Result<Unit> {
            // SQLite does not support setting the transaction isolation level.
            return Result.success(Unit)
        }

        override suspend fun execute(sql: String): Result<Long> = mutex.withLockCatching(::assertIsOpen) {
            withContext(dispatcher) { db.executeScript(sql).rowsAffected }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            executeWithResult(statement).map { it.rowsAffected }

        override suspend fun executeWithResult(sql: String): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) { withContext(dispatcher) { db.executeScript(sql) } }

        override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> {
            val nq = statement.render(encoders).getOrElse { return Result.failure(it) }
            return mutex.withLockCatching(::assertIsOpen) { withContext(dispatcher) { db.executeForResult(nq) } }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = mutex.withLockCatching(::assertIsOpen) {
            withContext(dispatcher) { db.rawQuery(sql, emptyList()).use { it.toResultSet() } }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> {
            val nq = statement.render(encoders).getOrElse { return Result.failure(it) }
            return mutex.withLockCatching(::assertIsOpen) {
                withContext(dispatcher) { db.rawQuery(nq).use { it.toResultSet() } }
            }
        }

        // The connection is held (its mutex locked) for the whole collection.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = mutex.flowWithLock(::assertIsOpen) {
            cursorRows(fetchSize, dispatcher) { db.rawQuery(sql, emptyList()) }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) {
                cursorRows(fetchSize, dispatcher) { db.rawQuery(statement.renderNativeQuery(Dialect.SQLite, encoders)) }
            }

        override suspend fun begin(): Result<Transaction> = mutex.withLockCatching(::assertIsOpen) {
            // Begin the transaction on the connection's dedicated thread, then hand
            // the same dispatcher to AndroidTransaction so commit/rollback also run
            // on that thread (Android SQLiteDatabase sessions are thread-local). To completion also when the caller is
            // cancelled meanwhile: withContext(dispatcher) would otherwise discard the transaction on return, left
            // open on the connection, and the one a later begin() nests in.
            withContext(NonCancellable) { withContext(dispatcher) {
                // A begin() while a transaction is open nests in the innermost one.
                val parent = transactions.lastOrNull { it.status == Transaction.Status.Open }
                db.beginTransactionNonExclusive()
                // Recorded as soon as it is begun: a caller cancelled meanwhile never receives it, and closing the
                // connection must still roll it back.
                AndroidTransaction(db, false, encoders, dispatcher, parent).also { tx ->
                    parent?.child = tx
                    transactions.removeAll { it.status != Transaction.Status.Open }
                    transactions += tx
                }
            } }
        }.mapCatching { tx ->
            // A caller cancelled meanwhile never receives it: it is rolled back.
            if (!currentCoroutineContext().isActive) {
                tx.rollback()
                currentCoroutineContext().ensureActive()
            }
            tx
        }
    }

    class AndroidTransaction(
        private val db: SQLiteDatabase,
        private val closeConnectionAfterTx: Boolean,
        override val encoders: ValueEncoderRegistry,
        // Must be the same dispatcher used by the owning AndroidConnection so that
        // beginTransaction/setTransactionSuccessful/endTransaction all run on the same thread.
        private val dispatcher: CoroutineDispatcher,
        // The transaction of the connection this one is nested in, if any.
        private val parent: AndroidTransaction? = null
    ) : Transaction {
        private val mutex = Mutex()

        // Nesting without savepoints (see below): a nested transaction is part of the outermost one, which commits
        // or rolls back the work of both. The fields below are read and written on the connection's dispatcher.

        // The transaction nested in this one, while it is open.
        internal var child: AndroidTransaction? = null

        // Set on the outermost transaction when one nested in it was rolled back: its work cannot be rolled back
        // alone, so the whole transaction can only be rolled back.
        private var rollbackOnly: Boolean = false
        private val outermostTransaction: AndroidTransaction get() = parent?.outermostTransaction ?: this
        private var _status: Transaction.Status = Transaction.Status.Open
        private var _commited: Boolean = false
        private var _rollbacked: Boolean = false
        override val status: Transaction.Status get() = _status
        override val commited: Boolean get() = _commited
        override val rollbacked: Boolean get() = _rollbacked

        // Savepoints cannot be offered on top of android.database.sqlite: the framework classifies every
        // statement by its first keyword before running it, and `ROLLBACK TO SAVEPOINT ...` is treated as
        // a plain ROLLBACK (SQLiteSession.executeSpecial), which ends the *whole* transaction instead of
        // rolling back to the savepoint.
        override suspend fun savepoint(name: String): Result<Unit> = savepointsUnsupported()
        override suspend fun releaseSavepoint(name: String): Result<Unit> = savepointsUnsupported()
        override suspend fun rollbackToSavepoint(name: String): Result<Unit> = savepointsUnsupported()

        private fun savepointsUnsupported(): Result<Unit> = Result.failure(
            SQLError(
                code = SQLError.Code.Database,
                message = "Savepoints are not supported by the Android platform SQLite driver: " +
                        "android.database.sqlite intercepts `ROLLBACK TO SAVEPOINT` and ends the whole transaction. " +
                        "Use sqlx4k-sqlite-cipher (sqlx core) if you need savepoints on Android."
            )
        )

        // Runs to completion also when the caller is cancelled (e.g. from the finally block of a cancelled scope):
        // withContext(dispatcher) would otherwise throw on entry, after the transaction was marked closed, and the
        // transaction would stay open on the connection, which goes back to the pool inside it.
        override suspend fun commit(): Result<Unit> = runCatching {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (commited) return@withLock
                    assertIsOpen()
                    // android.database.sqlite ends the innermost transaction, whichever one is ended, and left open,
                    // this one held its locks until the connection closed: both are rolled back.
                    if (withContext(dispatcher) { child } != null) {
                        rollbackLocked()
                        throw SQLError(
                            code = SQLError.Code.Database,
                            message = "The transaction was rolled back: one nested in it was still open when it was committed."
                        )
                    }
                    withContext(dispatcher) {
                        _status = Transaction.Status.Closed
                        parent?.child = null
                        if (parent == null && rollbackOnly) {
                            db.completeTransaction(closeConnectionAfterTx) { }
                            _rollbacked = true
                            throw SQLError(
                                code = SQLError.Code.Database,
                                message = "The transaction was rolled back, as one nested in it was: the Android " +
                                        "platform SQLite driver has no savepoints to roll back the nested one alone."
                            )
                        }
                        if (parent == null) db.commitOutermostTransaction(closeConnectionAfterTx)
                        else db.completeTransaction(closeConnectionAfterTx) { setTransactionSuccessful() }
                    }
                    // Only once the COMMIT itself succeeded (it runs when the transaction ends).
                    _commited = true
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
            // android.database.sqlite ends the innermost transaction, whichever one is ended.
            withContext(dispatcher) { child }?.rollback()?.getOrThrow()
            _status = Transaction.Status.Closed
            withContext(dispatcher) {
                parent?.child = null
                if (parent != null) outermostTransaction.rollbackOnly = true
                // Do NOT call setTransactionSuccessful() — endTransaction() will roll back.
                db.completeTransaction(closeConnectionAfterTx) { }
            }
            _rollbacked = true
        }

        override suspend fun execute(sql: String): Result<Long> = mutex.withLockCatching(::assertIsOpen) {
            withContext(dispatcher) { db.executeScript(sql).rowsAffected }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            executeWithResult(statement).map { it.rowsAffected }

        override suspend fun executeWithResult(sql: String): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) { withContext(dispatcher) { db.executeScript(sql) } }

        override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> {
            val nq = statement.render(encoders).getOrElse { return Result.failure(it) }
            return mutex.withLockCatching(::assertIsOpen) { withContext(dispatcher) { db.executeForResult(nq) } }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = mutex.withLockCatching(::assertIsOpen) {
            withContext(dispatcher) { db.rawQuery(sql, emptyList()).use { it.toResultSet() } }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> {
            val nq = statement.render(encoders).getOrElse { return Result.failure(it) }
            return mutex.withLockCatching(::assertIsOpen) {
                withContext(dispatcher) { db.rawQuery(nq).use { it.toResultSet() } }
            }
        }

        // The transaction is held (its mutex locked) for the whole collection.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = mutex.flowWithLock(::assertIsOpen) {
            cursorRows(fetchSize, dispatcher) { db.rawQuery(sql, emptyList()) }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) {
                cursorRows(fetchSize, dispatcher) { db.rawQuery(statement.renderNativeQuery(Dialect.SQLite, encoders)) }
            }
    }

    companion object {
        private fun createConnectionPool(
            context: Context,
            url: String,
            options: ConnectionPool.Options,
            encoders: ValueEncoderRegistry
        ): ConnectionPoolImpl {
            // Parse the URL: split the database name from the `?mode=...` query string, so the
            // parameters are honoured instead of ending up inside the file name.
            val parsed = SQLiteUrl.parse(url)
            parsed.params.keys.firstOrNull { it != SQLiteUrl.PARAM_MODE && it != SQLiteUrl.PARAM_CACHE }?.let { key ->
                SQLError(
                    SQLError.Code.Pool,
                    "Unsupported SQLite URL parameter `$key` on Android (in '$url'). " +
                            "Only `mode` is honoured (`cache` is accepted and ignored)."
                ).raise()
            }

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

            // Connection factory that creates Android SQLite connections. The database is opened to completion also
            // when the caller is cancelled meanwhile (an acquire timeout, ...), and then closed: dropped, it would stay
            // open until the garbage collector finalizes it. NonCancellable wraps the switch to Dispatchers.IO rather
            // than joining it: withContext(NonCancellable + Dispatchers.IO) returns into the caller's context, and
            // discards its result there when the caller is cancelled, so the database was dropped all the same.
            val connectionFactory: suspend () -> Connection = {
                val connection = withContext(NonCancellable) { withContext(Dispatchers.IO) {
                    val db = when {
                        isInMemory -> SQLiteDatabase.create(null)

                        // Default and `mode=rwc`: use Context to open/create the database, then enable
                        // WAL for better read concurrency (matches the JVM implementation behaviour).
                        parsed.mode == null || parsed.mode == SQLiteUrl.MODE_RWC ->
                            context.openOrCreateDatabase(parsed.database, Context.MODE_PRIVATE, null).also {
                                it.enableWriteAheadLogging()
                            }

                        // `mode=ro` / `mode=rw`: open the file the Context would resolve, but never
                        // create it. WAL only makes sense (and is only possible) for writable databases.
                        else -> {
                            val flags =
                                if (parsed.isReadOnly) SQLiteDatabase.OPEN_READONLY
                                else SQLiteDatabase.OPEN_READWRITE

                            val path = context.getDatabasePath(parsed.database).path
                            SQLiteDatabase.openDatabase(path, null, flags).also {
                                if (!parsed.isReadOnly) it.enableWriteAheadLogging()
                            }
                        }
                    }
                    // Enforce foreign keys, as the other targets do (SQLite leaves them off by default).
                    db.setForeignKeyConstraintsEnabled(true)
                    AndroidConnection(db, encoders)
                } }
                if (!currentCoroutineContext().isActive) {
                    connection.close()
                    currentCoroutineContext().ensureActive()
                }
                connection
            }

            return ConnectionPoolImpl(options, encoders, null, connectionFactory)
        }
    }
}
