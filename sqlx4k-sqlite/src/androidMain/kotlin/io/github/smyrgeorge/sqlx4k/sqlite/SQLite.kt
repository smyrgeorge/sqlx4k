@file:OptIn(ExperimentalUuidApi::class)
@file:Suppress("SqlSourceToSinkFlow", "DuplicatedCode")

package io.github.smyrgeorge.sqlx4k.sqlite

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.Transaction.IsolationLevel
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migration
import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import io.github.smyrgeorge.sqlx4k.impl.pool.ConnectionPoolImpl
import io.github.smyrgeorge.sqlx4k.impl.pool.PooledConnection
import io.github.smyrgeorge.sqlx4k.impl.pool.PooledTransaction
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * SQLite class provides mechanisms to interact with a SQLite database on the Android platform.
 * It implements `Driver`, `Driver.Pool`, and `Driver.Transactional` interfaces,
 * offering functionalities such as connection pooling, executing queries,
 * fetching data, and handling transactions.
 *
 * The URL format for SQLite can be one of the following:
 * - `sqlite::memory:` - Creates an in-memory database
 * - `sqlite:database.db` - Creates/opens a database file
 * - `sqlite:/path/to/database.db` - Uses absolute path
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
    init {
        // Register the SQLite-specific BLOB encoder unless the caller has overridden it.
        if (encoders.getTyped(ByteArray::class) == null) {
            encoders.register(ByteArrayEncoder)
        }
    }

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

    override suspend fun execute(sql: String): Result<Long> = runCatching {
        val connection = pool.acquire().getOrThrow()
        try {
            connection.execute(sql).getOrThrow()
        } catch (e: Exception) {
            SQLError(SQLError.Code.Database, e.message, e).raise()
        } finally {
            connection.close()
        }
    }

    override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
        val connection = pool.acquire().getOrThrow()
        try {
            connection.fetchAll(sql).getOrThrow()
        } catch (e: Exception) {
            SQLError(SQLError.Code.Database, e.message, e).raise()
        } finally {
            connection.close()
        }
    }

    override suspend fun execute(statement: Statement): Result<Long> = runCatching {
        val connection = pool.acquire().getOrThrow()
        try {
            connection.execute(statement).getOrThrow()
        } catch (e: Exception) {
            SQLError(SQLError.Code.Database, e.message, e).raise()
        } finally {
            connection.close()
        }
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> = runCatching {
        val connection = pool.acquire().getOrThrow()
        try {
            connection.fetchAll(statement).getOrThrow()
        } catch (e: Exception) {
            SQLError(SQLError.Code.Database, e.message, e).raise()
        } finally {
            connection.close()
        }
    }

    override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> = runCatching {
        fetchAll(statement).getOrThrow().let { rowMapper.map(it, encoders) }
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        val connection = pool.acquire().getOrThrow() as PooledConnection
        try {
            val tx = connection.begin().getOrThrow()
            // Wrap the transaction to ensure the pooled connection is released
            PooledTransaction(tx, connection)
        } catch (e: Exception) {
            connection.close()
            SQLError(SQLError.Code.Database, e.message, e).raise()
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

        override suspend fun close(): Result<Unit> = runCatching {
            mutex.withLock {
                if (status == Connection.Status.Closed) return@withLock
                _status = Connection.Status.Closed
                withContext(dispatcher) {
                    db.close()
                }
                executor.shutdown()
            }
        }

        override suspend fun setTransactionIsolationLevel(level: IsolationLevel): Result<Unit> {
            // SQLite does not support setting the transaction isolation level.
            return Result.success(Unit)
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                try {
                    withContext(dispatcher) {
                        db.execSQL(sql)
                        // execSQL returns void; return -1 as "unknown affected rows"
                        -1L
                    }
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message).raise()
                }
            }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                try {
                    withContext(dispatcher) {
                        db.rawQuery(sql, null).use { it.toResultSet() }
                    }.toResult()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message).raise()
                }
            }
        }

        override suspend fun begin(): Result<Transaction> = runCatching {
            mutex.withLock {
                assertIsOpen()
                // Begin the transaction on the connection's dedicated thread, then hand
                // the same dispatcher to AndroidTransaction so commit/rollback also run
                // on that thread (Android SQLiteDatabase sessions are thread-local).
                withContext(dispatcher) {
                    try {
                        db.beginTransactionNonExclusive()
                        AndroidTransaction(db, false, encoders, dispatcher)
                    } catch (e: Exception) {
                        SQLError(SQLError.Code.Database, e.message, e).raise()
                    }
                }
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

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                if (commited) return@withLock
                assertIsOpen()
                _status = Transaction.Status.Closed
                withContext(dispatcher) {
                    try {
                        db.setTransactionSuccessful()
                        _commited = true
                    } catch (e: Exception) {
                        SQLError(SQLError.Code.Database, e.message, e).raise()
                    } finally {
                        try {
                            db.endTransaction()
                            if (closeConnectionAfterTx) db.close()
                        } catch (_: Exception) {
                            // Ignore exceptions in finally block
                        }
                    }
                }
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                if (rollbacked) return@withLock
                assertIsOpen()
                _status = Transaction.Status.Closed
                withContext(dispatcher) {
                    try {
                        // Do NOT call setTransactionSuccessful() — endTransaction() will roll back.
                        _rollbacked = true
                    } catch (e: Exception) {
                        SQLError(SQLError.Code.Database, e.message, e).raise()
                    } finally {
                        try {
                            db.endTransaction()
                            if (closeConnectionAfterTx) db.close()
                        } catch (_: Exception) {
                            // Ignore exceptions in finally block
                        }
                    }
                }
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                try {
                    withContext(dispatcher) {
                        db.execSQL(sql)
                        -1L
                    }
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message).raise()
                }
            }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                try {
                    withContext(dispatcher) {
                        db.rawQuery(sql, null).use { it.toResultSet() }
                    }.toResult()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message).raise()
                }
            }
        }
    }

    companion object {
        private fun Cursor.toResultSet(): ResultSet {
            fun toRow(): ResultSet.Row {
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
                        Cursor.FIELD_TYPE_FLOAT -> if (isNull(i)) null else getDouble(i).toString()
                        Cursor.FIELD_TYPE_STRING -> if (isNull(i)) null else getString(i)
                        else -> if (isNull(i)) null else getString(i)
                    }
                    ResultSet.Row.Column(
                        ordinal = i,
                        name = getColumnName(i),
                        type = type,
                        value = if (value == "null") null else value,
                    )
                }
                return ResultSet.Row(columns)
            }

            val rows = mutableListOf<ResultSet.Row>()
            if (moveToFirst()) {
                do {
                    rows.add(toRow())
                } while (moveToNext())
            }
            val meta = if (rows.isEmpty()) ResultSet.Metadata(emptyList()) else rows.first().toMetadata()
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
            // Parse the URL to extract database name
            val dbUrl = url.removePrefix("sqlite:").removePrefix("//")

            // Validate in-memory database configuration
            // In-memory SQLite databases are isolated per connection, so pool size must be 1
            val isInMemory = dbUrl.equals(":memory:", ignoreCase = true)
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
                    val db = if (isInMemory) {
                        SQLiteDatabase.create(null)
                    } else {
                        // Use Context to open/create the database, then enable WAL for
                        // better read concurrency (matches the JVM implementation behaviour).
                        context.openOrCreateDatabase(dbUrl, Context.MODE_PRIVATE, null).also {
                            it.enableWriteAheadLogging()
                        }
                    }
                    AndroidConnection(db, encoders)
                }
            }

            return ConnectionPoolImpl(options, encoders, null, connectionFactory)
        }
    }
}
