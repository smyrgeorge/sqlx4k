@file:OptIn(ExperimentalUuidApi::class)
@file:Suppress("SqlSourceToSinkFlow", "DuplicatedCode")

package io.github.smyrgeorge.sqlx4k.sqlite

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
import java.sql.Connection as NativeJdbcConnection
import java.sql.DriverManager
import java.sql.ResultSet as NativeJdbcResultSet
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toLocalDateTime

/**
 * SQLite class provides mechanisms to interact with a SQLite database on the JVM platform.
 * It implements `Driver`, `Driver.Pool`, and `Driver.Transactional` interfaces,
 * offering functionalities such as connection pooling, executing queries,
 * fetching data, and handling transactions.
 *
 * The URL format for SQLite can be one of the following:
 * - `jdbc:sqlite::memory:` - Creates an in-memory database
 * - `jdbc:sqlite:database.db` - Creates/opens a database file
 * - `jdbc:sqlite:/path/to/database.db` - Uses absolute path
 *
 * If the URL does not start with "jdbc:sqlite:", it will be automatically prefixed.
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
    private val pool: ConnectionPoolImpl = createConnectionPool(url, options, encoders)

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

    class JdbcConnection(
        private val connection: NativeJdbcConnection,
        override val encoders: ValueEncoderRegistry
    ) : Connection {
        private val mutex = Mutex()
        private var _status: Connection.Status = Connection.Status.Open
        override val status: Connection.Status get() = _status
        override val transactionIsolationLevel: IsolationLevel? = null

        override suspend fun close(): Result<Unit> = runCatching {
            mutex.withLock {
                if (status == Connection.Status.Closed) return@withLock
                _status = Connection.Status.Closed
                withContext(Dispatchers.IO) {
                    connection.close()
                }
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
                    withContext(Dispatchers.IO) {
                        connection.createStatement().use { statement ->
                            statement.executeUpdate(sql).toLong()
                        }
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
                    withContext(Dispatchers.IO) {
                        connection.createStatement().use { stmt ->
                            stmt.executeQuery(sql).toResultSet()
                        }
                    }.toResult()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message).raise()
                }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                try {
                    withContext(Dispatchers.IO) {
                        connection.prepareStatement(statement, encoders).use { stmt ->
                            stmt.executeUpdate().toLong()
                        }
                    }
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message, e).raise()
                }
            }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                try {
                    withContext(Dispatchers.IO) {
                        connection.prepareStatement(statement, encoders).use { stmt ->
                            stmt.executeQuery().toResultSet()
                        }
                    }.toResult()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message, e).raise()
                }
            }
        }

        override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
            runCatching {
                fetchAll(statement).getOrThrow().let { rowMapper.map(it, encoders) }
            }

        override suspend fun begin(): Result<Transaction> = runCatching {
            mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    try {
                        connection.autoCommit = false
                    } catch (e: Exception) {
                        SQLError(SQLError.Code.Database, e.message, e).raise()
                    }
                    JdbcTransaction(connection, false, encoders)
                }
            }
        }
    }

    class JdbcTransaction(
        private var connection: NativeJdbcConnection,
        private val closeConnectionAfterTx: Boolean,
        override val encoders: ValueEncoderRegistry
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
                withContext(Dispatchers.IO) {
                    try {
                        connection.commit()
                        connection.autoCommit = true
                        _commited = true
                    } catch (e: Exception) {
                        SQLError(SQLError.Code.Database, e.message, e).raise()
                    } finally {
                        if (closeConnectionAfterTx) connection.close()
                    }
                }
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                if (rollbacked) return@withLock
                assertIsOpen()
                _status = Transaction.Status.Closed
                withContext(Dispatchers.IO) {
                    try {
                        connection.rollback()
                        connection.autoCommit = true
                        _rollbacked = true
                    } catch (e: Exception) {
                        SQLError(SQLError.Code.Database, e.message, e).raise()
                    } finally {
                        if (closeConnectionAfterTx) connection.close()
                    }
                }
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                try {
                    withContext(Dispatchers.IO) {
                        connection.createStatement().use { statement ->
                            statement.executeUpdate(sql).toLong()
                        }
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
                    withContext(Dispatchers.IO) {
                        connection.createStatement().use { stmt ->
                            stmt.executeQuery(sql).toResultSet()
                        }
                    }.toResult()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message).raise()
                }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                try {
                    withContext(Dispatchers.IO) {
                        connection.prepareStatement(statement, encoders).use { stmt ->
                            stmt.executeUpdate().toLong()
                        }
                    }
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message, e).raise()
                }
            }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                try {
                    withContext(Dispatchers.IO) {
                        connection.prepareStatement(statement, encoders).use { stmt ->
                            stmt.executeQuery().toResultSet()
                        }
                    }.toResult()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message, e).raise()
                }
            }
        }

        override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
            runCatching {
                fetchAll(statement).getOrThrow().let { rowMapper.map(it, encoders) }
            }
    }

    companion object {
        private fun NativeJdbcConnection.prepareStatement(
            statement: Statement,
            encoders: ValueEncoderRegistry
        ): java.sql.PreparedStatement {
            fun Any?.toJdbc(): Any? = when (this) {
                null -> null
                is Char -> toString()
                // SQLite stores dates/times as text. The shared decoder uses a space-separated
                // format ("yyyy-MM-dd HH:mm:ss"), so we must produce that format here.
                is Instant -> toLocalDateTime(TimeZone.UTC).toString().replace('T', ' ')
                is LocalDate -> toJavaLocalDate()
                is LocalTime -> toJavaLocalTime()
                is LocalDateTime -> toString().replace('T', ' ')
                // SQLite has no native UUID type; store as string.
                // Also handle java.util.UUID in case Kotlin's Uuid value class is stored as its underlying type.
                is Uuid -> toString()
                is java.util.UUID -> toString()
                else -> this
            }

            val query = statement.renderNativeQuery(Dialect.SQLite, encoders)
            val stmt = prepareStatement(query.sql)
            query.values.forEachIndexed { index, value ->
                stmt.setObject(index + 1, value?.toJdbc())
            }
            return stmt
        }

        private fun NativeJdbcResultSet.toResultSet(): ResultSet {
            fun toRow(): ResultSet.Row {
                val metaData = this.metaData
                val columns = (1..metaData.columnCount).map { i ->
                    val type = metaData.getColumnTypeName(i)
                    ResultSet.Row.Column(
                        ordinal = i - 1,
                        name = metaData.getColumnName(i),
                        type = metaData.getColumnTypeName(i),
                        value = if (type == "BLOB") getString(i).toByteArray().toHexString() else getString(i)
                    )
                }
                return ResultSet.Row(columns)
            }

            val rows = mutableListOf<ResultSet.Row>()
            while (next()) {
                rows.add(toRow())
            }
            val meta = if (rows.isEmpty()) ResultSet.Metadata(emptyList())
            else rows.first().toMetadata()
            return ResultSet(rows, null, meta)
        }

        private fun createConnectionPool(
            url: String,
            options: ConnectionPool.Options,
            encoders: ValueEncoderRegistry
        ): ConnectionPoolImpl {
            // Ensure the URL has the proper JDBC prefix
            val jdbcUrl = "jdbc:sqlite:${url.removePrefix("jdbc:").removePrefix("sqlite:").removePrefix("//")}"

            // Validate in-memory database configuration
            // In-memory SQLite databases are isolated per connection, so pool size must be 1
            val isInMemory = jdbcUrl.contains(":memory:", ignoreCase = true)
            if (isInMemory && options.maxConnections > 1) {
                throw IllegalArgumentException(
                    "SQLite in-memory databases cannot be used with connection pools larger than 1. " +
                            "Each connection creates a separate in-memory database instance. " +
                            "Please set ConnectionPool.Options(minConnections = 1, maxConnections = 1) for in-memory databases."
                )
            }

            // Connection factory that creates JDBC connections
            val connectionFactory: suspend () -> Connection = {
                withContext(Dispatchers.IO) {
                    val connection = DriverManager.getConnection(jdbcUrl)
                    connection.autoCommit = true
                    JdbcConnection(connection, encoders).apply {
                        // Enable WAL mode for file-based databases to improve concurrency
                        // In-memory databases don't support WAL mode
                        if (!isInMemory) {
                            execute("PRAGMA journal_mode=WAL").getOrThrow()
                        }
                    }
                }
            }

            return ConnectionPoolImpl(options, encoders, null, connectionFactory)
        }
    }
}
