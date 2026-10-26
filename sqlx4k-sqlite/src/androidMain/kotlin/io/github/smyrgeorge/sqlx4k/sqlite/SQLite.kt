@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
@file:Suppress("SqlSourceToSinkFlow", "DuplicatedCode")

package io.github.smyrgeorge.sqlx4k.sqlite

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
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
import io.github.smyrgeorge.sqlx4k.impl.extensions.isInsertStatement
import io.github.smyrgeorge.sqlx4k.impl.extensions.toTimeString
import io.github.smyrgeorge.sqlx4k.impl.extensions.toTimestampString
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migration
import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import io.github.smyrgeorge.sqlx4k.impl.types.SqlRawLiteral
import io.github.smyrgeorge.sqlx4k.impl.types.TypedNull
import io.github.smyrgeorge.sqlx4k.sqlite.pool.ConnectionPoolImpl
import io.github.smyrgeorge.sqlx4k.sqlite.pool.PooledTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
            withContext(dispatcher) {
                db.execSQL(sql)
                // execSQL returns void; return -1 as "unknown affected rows"
                -1L
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            executeWithResult(statement).map { it.rowsAffected }

        override suspend fun executeWithResult(sql: String): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) { withContext(dispatcher) { db.executeForResult(sql) } }

        override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> {
            val nq = statement.render(encoders).getOrElse { return Result.failure(it) }
            return mutex.withLockCatching(::assertIsOpen) { withContext(dispatcher) { db.executeForResult(nq) } }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = mutex.withLockCatching(::assertIsOpen) {
            withContext(dispatcher) { db.rawQuery(sql, null).use { it.toResultSet() } }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> {
            val nq = statement.render(encoders).getOrElse { return Result.failure(it) }
            return mutex.withLockCatching(::assertIsOpen) {
                withContext(dispatcher) { db.rawQuery(nq).use { it.toResultSet() } }
            }
        }

        // The connection is held (its mutex locked) for the whole collection.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = mutex.flowWithLock(::assertIsOpen) {
            cursorRows(fetchSize, dispatcher) { db.rawQuery(sql, null) }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) {
                cursorRows(fetchSize, dispatcher) { db.rawQuery(statement.renderNativeQuery(Dialect.SQLite, encoders)) }
            }

        override suspend fun begin(): Result<Transaction> = mutex.withLockCatching(::assertIsOpen) {
            // Begin the transaction on the connection's dedicated thread, then hand
            // the same dispatcher to AndroidTransaction so commit/rollback also run
            // on that thread (Android SQLiteDatabase sessions are thread-local).
            withContext(dispatcher) {
                db.beginTransactionNonExclusive()
                AndroidTransaction(db, false, encoders, dispatcher)
            }
        }
    }

    class AndroidTransaction(
        private val db: SQLiteDatabase,
        private val closeConnectionAfterTx: Boolean,
        override val encoders: ValueEncoderRegistry,
        // Must be the same dispatcher used by the owning AndroidConnection so that
        // beginTransaction/setTransactionSuccessful/endTransaction all run on the same thread.
        private val dispatcher: CoroutineDispatcher
    ) : Transaction {
        private val mutex = Mutex()
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

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                if (commited) return@withLock
                assertIsOpen()
                _status = Transaction.Status.Closed
                withContext(dispatcher) {
                    db.completeTransaction(closeConnectionAfterTx) { setTransactionSuccessful() }
                }
                // Only once the COMMIT itself succeeded (it runs when the transaction ends).
                _commited = true
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                if (rollbacked) return@withLock
                assertIsOpen()
                _status = Transaction.Status.Closed
                withContext(dispatcher) {
                    // Do NOT call setTransactionSuccessful() — endTransaction() will roll back.
                    db.completeTransaction(closeConnectionAfterTx) { }
                }
                _rollbacked = true
            }
        }

        override suspend fun execute(sql: String): Result<Long> = mutex.withLockCatching(::assertIsOpen) {
            withContext(dispatcher) {
                db.execSQL(sql)
                -1L
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            executeWithResult(statement).map { it.rowsAffected }

        override suspend fun executeWithResult(sql: String): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) { withContext(dispatcher) { db.executeForResult(sql) } }

        override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> {
            val nq = statement.render(encoders).getOrElse { return Result.failure(it) }
            return mutex.withLockCatching(::assertIsOpen) { withContext(dispatcher) { db.executeForResult(nq) } }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = mutex.withLockCatching(::assertIsOpen) {
            withContext(dispatcher) { db.rawQuery(sql, null).use { it.toResultSet() } }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> {
            val nq = statement.render(encoders).getOrElse { return Result.failure(it) }
            return mutex.withLockCatching(::assertIsOpen) {
                withContext(dispatcher) { db.rawQuery(nq).use { it.toResultSet() } }
            }
        }

        // The transaction is held (its mutex locked) for the whole collection.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = mutex.flowWithLock(::assertIsOpen) {
            cursorRows(fetchSize, dispatcher) { db.rawQuery(sql, null) }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) {
                cursorRows(fetchSize, dispatcher) { db.rawQuery(statement.renderNativeQuery(Dialect.SQLite, encoders)) }
            }
    }

    companion object {
        /**
         * Binds the supplied positional values onto an Android [SQLiteProgram]
         * (`SQLiteStatement` for `compileStatement(sql)` or `SQLiteQuery` from a
         * cursor factory). Mirrors the JVM `setObject` mapping — Char / Instant /
         * `LocalDate*` / `Uuid` are folded down to text, ByteArray to BLOB,
         * primitives to LONG / DOUBLE.
         */
        private fun SQLiteProgram.bindAll(values: List<Any?>) {
            values.forEachIndexed { i, value ->
                val index = i + 1
                when (value) {
                    null -> bindNull(index)
                    is TypedNull -> bindNull(index)
                    is Boolean -> bindLong(index, if (value) 1L else 0L)
                    is Byte -> bindLong(index, value.toLong())
                    is Short -> bindLong(index, value.toLong())
                    is Int -> bindLong(index, value.toLong())
                    is Long -> bindLong(index, value)
                    is Float -> bindDouble(index, value.toDouble())
                    is Double -> bindDouble(index, value)
                    is String -> bindString(index, value)
                    is Char -> bindString(index, value.toString())
                    is ByteArray -> bindBlob(index, value)
                    // SQLite stores dates/times as text; match the canonical
                    // `yyyy-MM-dd HH:mm:ss.uuuuuu` shape so round-trips through
                    // the existing `asInstant()` / `asLocalDate*()` decoders work.
                    is Instant -> bindString(index, value.toTimestampString())
                    is LocalDate -> bindString(index, value.toString())
                    is LocalTime -> bindString(index, value.toTimeString())
                    is LocalDateTime -> bindString(index, value.toTimestampString())
                    is Uuid -> bindString(index, value.toString())
                    is java.util.UUID -> bindString(index, value.toString())
                    is SqlRawLiteral -> bindString(index, value.sql)
                    else -> SQLError(
                        code = SQLError.Code.MissingValueConverter,
                        message = "Cannot bind value of type ${value::class.simpleName} as a SQLite parameter"
                    ).raise()
                }
            }
        }

        /**
         * Renders the statement for SQLite. Rendering runs before the driver call, outside its error mapping, so a
         * rendering failure is reported as it is.
         */
        private fun Statement.render(encoders: ValueEncoderRegistry): Result<Statement.NativeQuery> =
            runCatching { renderNativeQuery(Dialect.SQLite, encoders) }

        /**
         * Runs the query with its parameters bound by type. `rawQueryWithFactory` is the only public API that lets us
         * bind typed parameters (long / double / blob / string / null) to a SELECT — `rawQuery` would force every arg
         * through `bindString`. `editTable` is documented as a hint for the cursor (only used by Android's
         * CursorAdapter / undo support); an empty string satisfies the non-null Kotlin signature without consequence.
         */
        private fun SQLiteDatabase.rawQuery(query: Statement.NativeQuery): Cursor {
            val factory = SQLiteDatabase.CursorFactory { _, driver, editTable, program ->
                program.bindAll(query.values)
                SQLiteCursor(driver, editTable, program)
            }
            return rawQueryWithFactory(factory, query.sql, null, "")
        }

        private fun SQLiteDatabase.executeForResult(sql: String): ExecuteResult =
            executeForResult(sql) { compileStatement(sql).use { it.executeUpdateDelete() } }

        private fun SQLiteDatabase.executeForResult(query: Statement.NativeQuery): ExecuteResult =
            executeForResult(query.sql) {
                compileStatement(query.sql).use { stmt ->
                    stmt.bindAll(query.values)
                    stmt.executeUpdateDelete()
                }
            }

        /**
         * Runs [update] (returning the changed row count) and reports the rowid an `INSERT` generated. SQLite keeps the
         * last inserted rowid per connection, and outside a transaction SQLiteDatabase may run the follow-up query on
         * another of its connections: the insert and the read share a transaction, and with it one connection.
         */
        private fun SQLiteDatabase.executeForResult(sql: String, update: () -> Int): ExecuteResult {
            if (!sql.isInsertStatement()) return ExecuteResult(rowsAffected = update().toLong(), lastInsertId = null)
            val ownTransaction = !inTransaction()
            if (ownTransaction) beginTransactionNonExclusive()
            try {
                val rowsAffected = update().toLong()
                val lastInsertId = if (rowsAffected > 0) {
                    compileStatement("select last_insert_rowid()").use { it.simpleQueryForLong() }
                } else {
                    null
                }
                if (ownTransaction) setTransactionSuccessful()
                return ExecuteResult(rowsAffected = rowsAffected, lastInsertId = lastInsertId)
            } finally {
                if (ownTransaction) endTransaction()
            }
        }

        /** The row the cursor is positioned on. */
        private fun Cursor.toRow(): ResultSet.Row {
            val columns = (0 until columnCount).map { i ->
                val type = getType(i).toTypeName()
                if (getType(i) == Cursor.FIELD_TYPE_BLOB) {
                    return@map ResultSet.Row.Column(
                        ordinal = i,
                        name = getColumnName(i),
                        type = type,
                        value = null,
                        bytes = if (isNull(i)) null else getBlob(i),
                    )
                }
                val value = when (getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> if (isNull(i)) null else getLong(i).toString()
                    Cursor.FIELD_TYPE_FLOAT -> if (isNull(i)) null else formatSqliteReal(getDouble(i))
                    Cursor.FIELD_TYPE_STRING -> if (isNull(i)) null else getString(i)
                    else -> if (isNull(i)) null else getString(i)
                }
                ResultSet.Row.Column(
                    ordinal = i,
                    name = getColumnName(i),
                    type = type,
                    value = value,
                )
            }
            return ResultSet.Row(columns)
        }

        /**
         * Streams the rows of a query: the cursor is opened and stepped row by row on the connection's
         * [dispatcher] (the platform driver is blocking and its transaction state is thread-bound), handing
         * the rows to the collector through a buffer of [fetchSize] rows; the cursor is closed when the flow
         * completes, fails or is cancelled. A driver failure fails the collection with the matching [SQLError].
         *
         * @param query runs the query and returns its cursor.
         */
        private fun cursorRows(
            fetchSize: Int,
            dispatcher: CoroutineDispatcher,
            query: () -> Cursor
        ): Flow<ResultSet.Row> = flow {
            query().use { cursor ->
                while (cursor.moveToNext()) emit(cursor.toRow())
            }
        }.buffer(fetchSize.coerceAtLeast(1)).flowOn(dispatcher).catch { e -> throw (e as? SQLError) ?: e.toSQLError() }

        private fun Cursor.toResultSet(): ResultSet {
            val rows = mutableListOf<ResultSet.Row>()
            if (moveToFirst()) {
                do {
                    rows.add(toRow())
                } while (moveToNext())
            }
            // Derive metadata from the first row when available; on an empty result the cursor still
            // exposes column names/ordinals (SQLite types are per-value, so type is reported as "UNKNOWN").
            val meta = if (rows.isNotEmpty()) {
                rows.first().toMetadata()
            } else {
                ResultSet.Metadata(
                    (0 until columnCount).map { i ->
                        ResultSet.Metadata.Column(ordinal = i, name = getColumnName(i), type = "UNKNOWN")
                    }
                )
            }
            return ResultSet(rows, null, meta)
        }

        private fun Int.toTypeName(): String = when (this) {
            Cursor.FIELD_TYPE_NULL -> "NULL"
            Cursor.FIELD_TYPE_INTEGER -> "INTEGER"
            Cursor.FIELD_TYPE_FLOAT -> "REAL"
            Cursor.FIELD_TYPE_STRING -> "TEXT"
            Cursor.FIELD_TYPE_BLOB -> "BLOB"
            else -> "UNKNOWN"
        }

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

            // Connection factory that creates Android SQLite connections
            val connectionFactory: suspend () -> Connection = {
                withContext(Dispatchers.IO) {
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
                    AndroidConnection(db, encoders)
                }
            }

            return ConnectionPoolImpl(options, encoders, null, connectionFactory)
        }
    }
}
