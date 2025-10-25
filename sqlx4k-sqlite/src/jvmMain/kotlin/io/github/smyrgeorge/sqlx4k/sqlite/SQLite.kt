package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.*
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migration
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import io.github.smyrgeorge.sqlx4k.impl.pool.ConnectionPoolImpl
import io.github.smyrgeorge.sqlx4k.impl.pool.PooledConnection
import io.github.smyrgeorge.sqlx4k.impl.pool.PooledTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.sql.DriverManager
import kotlin.time.Duration
import java.sql.Connection as JdbcConnection
import java.sql.ResultSet as JdbcResultSet

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
 */
class SQLite(
    url: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
) : ISQLite {
    private val pool: ConnectionPoolImpl = createConnectionPool(url, options)

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
            SQLError(SQLError.Code.Database, e.message).ex()
        } finally {
            connection.close()
        }
    }

    override suspend fun execute(statement: Statement): Result<Long> =
        execute(statement.render(encoders))

    override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
        val connection = pool.acquire().getOrThrow()
        try {
            connection.fetchAll(sql).getOrThrow()
        } catch (e: Exception) {
            SQLError(SQLError.Code.Database, e.message).ex()
        } finally {
            connection.close()
        }
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        fetchAll(statement.render(encoders))

    override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
        fetchAll(statement.render(encoders), rowMapper)

    override suspend fun begin(): Result<Transaction> = runCatching {
        val connection = pool.acquire().getOrThrow() as PooledConnection
        try {
            val tx = connection.begin().getOrThrow()
            // Wrap the transaction to ensure the pooled connection is released
            PooledTransaction(tx, connection)
        } catch (e: Exception) {
            connection.close()
            SQLError(SQLError.Code.Database, e.message).ex()
        }
    }

    /**
     * A concrete implementation of the `Connection` interface that manages a single JDBC connection
     * while ensuring thread-safety and proper lifecycle handling.
     *
     * This class wraps a JDBC `Connection` and provides methods for executing queries, managing transactions,
     * and fetching results. It uses a mutex to synchronize operations and ensures the connection is in the
     * correct state before performing any operations. It tracks the connection's status internally and supports
     * releasing resources appropriately.
     *
     * @constructor Creates an instance of `Cn` with the specified JDBC `Connection`.
     * @property connection The underlying JDBC `Connection` used for executing database queries and transactions.
     */
    class Cn(
        private val connection: JdbcConnection
    ) : Connection {
        private val mutex = Mutex()
        private var _status: Connection.Status = Connection.Status.Open
        override val status: Connection.Status get() = _status

        override suspend fun close(): Result<Unit> = runCatching {
            mutex.withLock {
                assertIsOpen()
                _status = Connection.Status.Closed
                withContext(Dispatchers.IO) {
                    connection.close()
                }
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    @Suppress("SqlSourceToSinkFlow")
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(sql).toLong()
                    }
                }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            execute(statement.render(encoders))

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    @Suppress("SqlSourceToSinkFlow")
                    connection.createStatement().use { stmt ->
                        stmt.executeQuery(sql).toResultSet()
                    }
                }.toResult()
            }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            fetchAll(statement.render(encoders))

        override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
            fetchAll(statement.render(encoders), rowMapper)

        override suspend fun begin(): Result<Transaction> = runCatching {
            mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    try {
                        connection.autoCommit = false
                    } catch (e: Exception) {
                        SQLError(SQLError.Code.Database, e.message).ex()
                    }
                    Tx(connection, false)
                }
            }
        }

        override fun encoders(): Statement.ValueEncoderRegistry = encoders
    }

    /**
     * Represents a database transaction that uses a JDBC connection for transactional operations.
     *
     * This class implements the [Transaction] interface and provides functionality to manage the lifecycle
     * of a transaction, including committing, rolling back, and executing SQL statements. It ensures thread-safety
     * and consistency using a coroutine-based mutex to synchronize operations on the transaction.
     *
     * @constructor Creates a new transaction instance with a specific JDBC connection.
     * @param connection The JDBC connection used for the transaction.
     * @param closeConnectionAfterTx Indicates whether the connection should be closed after the transaction is finalized.
     */
    class Tx(
        private var connection: JdbcConnection,
        private val closeConnectionAfterTx: Boolean
    ) : Transaction {
        private val mutex = Mutex()
        private var _status: Transaction.Status = Transaction.Status.Open
        override val status: Transaction.Status get() = _status

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                assertIsOpen()
                _status = Transaction.Status.Closed
                withContext(Dispatchers.IO) {
                    try {
                        connection.commit()
                        connection.autoCommit = true
                    } catch (e: Exception) {
                        SQLError(SQLError.Code.Database, e.message).ex()
                    } finally {
                        if (closeConnectionAfterTx) connection.close()
                    }
                }
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                assertIsOpen()
                _status = Transaction.Status.Closed
                withContext(Dispatchers.IO) {
                    try {
                        connection.rollback()
                        connection.autoCommit = true
                    } catch (e: Exception) {
                        SQLError(SQLError.Code.Database, e.message).ex()
                    } finally {
                        if (closeConnectionAfterTx) connection.close()
                    }
                }
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    @Suppress("SqlSourceToSinkFlow")
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(sql).toLong()
                    }
                }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            execute(statement.render(encoders))

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    @Suppress("SqlSourceToSinkFlow")
                    connection.createStatement().use { stmt ->
                        stmt.executeQuery(sql).toResultSet()
                    }
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
         * This registry is used in methods like `execute`, `fetchAll`, and other database operation methods to ensure
         * that parameters bound to SQL statements are correctly encoded before being executed.
         */
        val encoders = Statement.ValueEncoderRegistry()

        private fun JdbcResultSet.toResultSet(): ResultSet {
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

        private fun createConnectionPool(url: String, options: ConnectionPool.Options): ConnectionPoolImpl {
            // Ensure the URL has the proper JDBC prefix
            val jdbcUrl = "jdbc:sqlite:${url.removePrefix("jdbc:").removePrefix("sqlite:").removePrefix("//")}"

            // Connection factory that creates JDBC connections
            val connectionFactory: suspend () -> Connection = {
                withContext(Dispatchers.IO) {
                    val jdbcConnection = DriverManager.getConnection(jdbcUrl)
                    jdbcConnection.autoCommit = true
                    Cn(jdbcConnection)
                }
            }

            return ConnectionPoolImpl(options, null, connectionFactory)
        }
    }
}
