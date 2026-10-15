@file:Suppress("SqlSourceToSinkFlow", "DuplicatedCode")

package io.github.smyrgeorge.sqlx4k.mysql

import io.asyncer.r2dbc.mysql.MySqlConnectionConfiguration
import io.asyncer.r2dbc.mysql.MySqlConnectionFactory
import io.asyncer.r2dbc.mysql.MySqlParameter
import io.asyncer.r2dbc.mysql.api.MySqlReadableMetadata
import io.asyncer.r2dbc.mysql.codec.Codec
import io.asyncer.r2dbc.mysql.codec.CodecContext
import io.asyncer.r2dbc.mysql.codec.CodecRegistry
import io.asyncer.r2dbc.mysql.constant.SslMode
import io.asyncer.r2dbc.mysql.extension.CodecRegistrar
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
import io.github.smyrgeorge.sqlx4k.impl.types.TypedNull
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.r2dbc.pool.ConnectionPool as NativeR2dbcConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.Connection as NativeR2dbcConnection
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.R2dbcTimeoutException
import io.r2dbc.spi.Result as NativeR2dbcResultSet
import io.r2dbc.spi.Row
import kotlin.jvm.optionals.getOrElse
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitLast
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toLocalDateTime
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import reactor.pool.PoolAcquireTimeoutException
import reactor.pool.PoolShutdownException

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
class MySQL(
    url: String,
    username: String,
    password: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
    override val encoders: ValueEncoderRegistry = ValueEncoderRegistry()
) : IMySQL {
    private val connectionFactory: MySqlConnectionFactory = connectionFactory(url, username, password)
    private val poolConfiguration: ConnectionPoolConfiguration = connectionPoolConfiguration(options, connectionFactory)
    private val pool: NativeR2dbcConnectionPool = NativeR2dbcConnectionPool(poolConfiguration).apply {
        runBlocking { launch { runCatching { warmup().awaitSingle() } } }
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
        try {
            pool.disposeLater().awaitSingleOrNull()
        } catch (e: Exception) {
            SQLError(SQLError.Code.WorkerCrashed, e.message, e).raise()
        }
    }

    override fun poolSize(): Int = pool.metrics.getOrElse { error("No metrics available.") }.allocatedSize()
    override fun poolIdleSize(): Int = pool.metrics.getOrElse { error("No metrics available.") }.idleSize()

    override suspend fun acquire(): Result<Connection> = runCatching {
        R2dbcConnection(pool.acquire(), encoders)
    }

    override suspend fun execute(sql: String): Result<Long> = executeWithResult(sql).map { it.rowsAffected }

    override suspend fun executeWithResult(sql: String): Result<ExecuteResult> = runCatching {
        with(pool.acquire()) {
            try {
                createStatement(sql).executeForResult()
            } catch (e: Exception) {
                e.toSQLError().raise()
            } finally {
                close().toMono().awaitSingleOrNull()
            }
        }
    }

    override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
        with(pool.acquire()) {
            try {
                createStatement(sql).execute().awaitLast().toResultSet()
            } catch (e: Exception) {
                e.toSQLError().raise()
            } finally {
                close().toMono().awaitSingleOrNull()
            }
        }
    }

    override suspend fun execute(statement: Statement): Result<Long> =
        executeWithResult(statement).map { it.rowsAffected }

    override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> = runCatching {
        with(pool.acquire()) {
            try {
                createStatement(statement, encoders).executeForResult()
            } catch (e: Exception) {
                e.toSQLError().raise()
            } finally {
                close().toMono().awaitSingleOrNull()
            }
        }
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> = runCatching {
        with(pool.acquire()) {
            try {
                createStatement(statement, encoders).execute().awaitLast().toResultSet()
            } catch (e: Exception) {
                e.toSQLError().raise()
            } finally {
                close().toMono().awaitSingleOrNull()
            }
        }
    }

    // A pooled connection is borrowed for the whole collection and returned when the flow completes,
    // fails or is cancelled.
    override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = flow {
        val connection = pool.acquire()
        try {
            emitAll(connection.createStatement(sql).fetchSize(fetchSize).streamRows())
        } finally {
            withContext(NonCancellable) { connection.close().toMono().awaitSingleOrNull() }
        }
    }

    override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> = flow {
        val connection = pool.acquire()
        try {
            emitAll(connection.createStatement(statement, encoders).fetchSize(fetchSize).streamRows())
        } finally {
            withContext(NonCancellable) { connection.close().toMono().awaitSingleOrNull() }
        }
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        with(pool.acquire()) {
            try {
                beginTransaction().toMono().awaitSingleOrNull()
            } catch (e: Exception) {
                close().toMono().awaitSingleOrNull()
                e.toSQLError().raise()
            }
            R2dbcTransaction(this, true, encoders)
        }
    }

    private suspend fun NativeR2dbcConnectionPool.acquire(): NativeR2dbcConnection {
        return try {
            create().awaitSingle()
        } catch (e: Exception) {
            when (e) {
                is PoolShutdownException -> SQLError(SQLError.Code.PoolClosed, e.message).raise()
                is PoolAcquireTimeoutException -> SQLError(SQLError.Code.PoolTimedOut, e.message).raise()
                // r2dbc-pool enforces the acquire timeout itself and reports it as an R2dbcTimeoutException.
                is R2dbcTimeoutException -> SQLError(SQLError.Code.PoolTimedOut, e.message).raise()
                else -> SQLError(SQLError.Code.Pool, e.message, e).raise()
            }
        }
    }

    class R2dbcConnection(
        private val connection: NativeR2dbcConnection,
        override val encoders: ValueEncoderRegistry
    ) : Connection {
        private val mutex = Mutex()
        private var _status: Connection.Status = Connection.Status.Open
        override val status: Connection.Status get() = _status
        private var _transactionIsolationLevel: IsolationLevel? = null
        override val transactionIsolationLevel: IsolationLevel? get() = _transactionIsolationLevel

        override suspend fun close(): Result<Unit> = runCatching {
            mutex.withLock {
                if (status == Connection.Status.Closed) return@withLock
                _status = Connection.Status.Closed

                try {
                    transactionIsolationLevel?.let {
                        val default = IMySQL.DEFAULT_TRANSACTION_ISOLATION_LEVEL
                        setTransactionIsolationLevel(default, false).getOrThrow()
                    }
                } finally {
                    connection.close().toMono().awaitSingleOrNull()
                }
            }
        }

        private suspend fun setTransactionIsolationLevel(level: IsolationLevel, lock: Boolean): Result<Unit> {
            // language=MySQL
            val sql = "SET SESSION TRANSACTION ISOLATION LEVEL ${level.value}"
            return execute(sql, lock).map { }.onSuccess { _transactionIsolationLevel = level }
        }

        override suspend fun setTransactionIsolationLevel(level: IsolationLevel): Result<Unit> =
            setTransactionIsolationLevel(level, true)

        private suspend fun execute(sql: String, lock: Boolean): Result<Long> =
            executeWithResult(sql, lock).map { it.rowsAffected }

        private suspend fun executeWithResult(sql: String, lock: Boolean): Result<ExecuteResult> {
            suspend fun doExecute(sql: String): Result<ExecuteResult> = runCatching {
                connection.createStatement(sql).executeForResult()
            }

            suspend fun doExecuteWithLock(sql: String): Result<ExecuteResult> = runCatching {
                mutex.withLock {
                    assertIsOpen()
                    doExecute(sql).getOrThrow()
                }
            }

            return if (lock) doExecuteWithLock(sql) else doExecute(sql)
        }

        override suspend fun execute(sql: String): Result<Long> = execute(sql, true)

        override suspend fun executeWithResult(sql: String): Result<ExecuteResult> = executeWithResult(sql, true)

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                try {
                    connection.createStatement(sql).execute().awaitLast().toResultSet().toResult()
                } catch (e: Exception) {
                    e.toSQLError().raise()
                }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            executeWithResult(statement).map { it.rowsAffected }

        override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> = runCatching {
            mutex.withLock {
                assertIsOpen()
                try {
                    connection.createStatement(statement, encoders).executeForResult()
                } catch (e: Exception) {
                    e.toSQLError().raise()
                }
            }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                try {
                    connection.createStatement(statement, encoders).execute().awaitLast().toResultSet().toResult()
                } catch (e: Exception) {
                    e.toSQLError().raise()
                }
            }
        }

        // The connection is held (its mutex locked) for the whole collection.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = flow {
            mutex.withLock {
                assertIsOpen()
                emitAll(connection.createStatement(sql).fetchSize(fetchSize).streamRows())
            }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> = flow {
            mutex.withLock {
                assertIsOpen()
                emitAll(connection.createStatement(statement, encoders).fetchSize(fetchSize).streamRows())
            }
        }

        override suspend fun begin(): Result<Transaction> = runCatching {
            mutex.withLock {
                assertIsOpen()
                try {
                    connection.beginTransaction().toMono().awaitSingleOrNull()
                } catch (e: Exception) {
                    e.toSQLError().raise()
                }
                R2dbcTransaction(connection, false, encoders)
            }
        }
    }

    class R2dbcTransaction(
        private val connection: NativeR2dbcConnection,
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
                try {
                    connection.commitTransaction().toMono().awaitSingleOrNull()
                    _commited = true
                } catch (e: Exception) {
                    e.toSQLError().raise()
                } finally {
                    if (closeConnectionAfterTx) connection.close().toMono().awaitSingleOrNull()
                }
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                if (rollbacked) return@withLock
                assertIsOpen()
                _status = Transaction.Status.Closed
                try {
                    connection.rollbackTransaction().toMono().awaitSingleOrNull()
                    _rollbacked = true
                } catch (e: Exception) {
                    e.toSQLError().raise()
                } finally {
                    if (closeConnectionAfterTx) connection.close().toMono().awaitSingleOrNull()
                }
            }
        }

        override suspend fun execute(sql: String): Result<Long> = executeWithResult(sql).map { it.rowsAffected }

        override suspend fun executeWithResult(sql: String): Result<ExecuteResult> = runCatching {
            mutex.withLock {
                assertIsOpen()
                try {
                    connection.createStatement(sql).executeForResult()
                } catch (e: Exception) {
                    e.toSQLError().raise()
                }
            }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                try {
                    connection.createStatement(sql).execute().awaitLast().toResultSet().toResult()
                } catch (e: Exception) {
                    e.toSQLError().raise()
                }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            executeWithResult(statement).map { it.rowsAffected }

        override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> = runCatching {
            mutex.withLock {
                assertIsOpen()
                try {
                    connection.createStatement(statement, encoders).executeForResult()
                } catch (e: Exception) {
                    e.toSQLError().raise()
                }
            }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                try {
                    connection.createStatement(statement, encoders).execute().awaitLast().toResultSet().toResult()
                } catch (e: Exception) {
                    e.toSQLError().raise()
                }
            }
        }

        // The transaction is held (its mutex locked) for the whole collection.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = flow {
            mutex.withLock {
                assertIsOpen()
                emitAll(connection.createStatement(sql).fetchSize(fetchSize).streamRows())
            }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> = flow {
            mutex.withLock {
                assertIsOpen()
                emitAll(connection.createStatement(statement, encoders).fetchSize(fetchSize).streamRows())
            }
        }
    }

    companion object {
        private fun <T> Publisher<T>.toMono(): Mono<T> {
            if (this is Mono<T>) return this
            error("Publisher is not a Mono: ${this::class.qualifiedName}")
        }

        /**
         * Executes the statement and reports its update count and generated id. With returnGeneratedValues(),
         * r2dbc-mysql reports `LAST_INSERT_ID()` from the server's reply to the statement (no extra query); it is 0
         * when the statement generated no id.
         */
        private suspend fun io.r2dbc.spi.Statement.executeForResult(): ExecuteResult {
            val segments = returnGeneratedValues().execute().awaitLast()
                .flatMap { segment ->
                    // An error arrives as a message segment: fail like rowsUpdated would.
                    if (segment is NativeR2dbcResultSet.Message) return@flatMap Mono.error(segment.exception())
                    // A segment may carry both: the update count and the generated id.
                    val count = (segment as? NativeR2dbcResultSet.UpdateCount)?.value() ?: 0L
                    val id = (segment as? NativeR2dbcResultSet.RowSegment)?.row()?.get(0, java.lang.Long::class.java)
                    Mono.just(count to id?.toLong())
                }
                .asFlow().toList()
            return ExecuteResult(
                rowsAffected = segments.sumOf { it.first },
                lastInsertId = segments.firstNotNullOfOrNull { it.second }?.takeIf { it != 0L },
            )
        }

        private fun NativeR2dbcConnection.createStatement(
            statement: Statement,
            encoders: ValueEncoderRegistry
        ): io.r2dbc.spi.Statement {
            fun Any.toR2dbc(): Any = when (this) {
                is Char -> toString()
                // MySQL DATETIME is timezone-agnostic; convert to UTC LocalDateTime to
                // avoid session-timezone shifts when the driver binds java.time.Instant.
                is Instant -> toLocalDateTime(TimeZone.UTC).toJavaLocalDateTime()
                is LocalDate -> toJavaLocalDate()
                is LocalTime -> toJavaLocalTime()
                is LocalDateTime -> toJavaLocalDateTime()
                // MySQL has no native UUID type; pass as string.
                // Also handle java.util.UUID since Kotlin's Uuid value class may be stored
                // as its underlying type when boxed as Any?.
                is Uuid -> toString()
                is java.util.UUID -> toString()
                else -> this
            }

            val query = statement.renderNativeQuery(Dialect.MySQL, encoders)
            val stmt = createStatement(query.sql)
            query.values.forEachIndexed { index, value ->
                when (value) {
                    is TypedNull -> stmt.bindNull(index, value.type.java)
                    null -> stmt.bindNull(index, Any::class.java)
                    else -> stmt.bind(index, value.toR2dbc())
                }
            }
            return stmt
        }

        private fun connectionFactory(url: String, username: String, password: String): MySqlConnectionFactory {
            val url = if (!url.startsWith("r2dbc:")) "r2dbc:$url" else url
            val options = ConnectionFactoryOptions
                .builder()
                .from(ConnectionFactoryOptions.parse(url))
                .option(ConnectionFactoryOptions.USER, username)
                .option(ConnectionFactoryOptions.PASSWORD, password)
                .build()

            val builder = MySqlConnectionConfiguration.builder()
                .host(options.getRequiredValue(ConnectionFactoryOptions.HOST) as String)
                .port(options.getValue(ConnectionFactoryOptions.PORT) as Int)
                .database(options.getValue(ConnectionFactoryOptions.DATABASE) as String)
                .username(options.getRequiredValue(ConnectionFactoryOptions.USER) as String)
                .password(options.getValue(ConnectionFactoryOptions.PASSWORD) as CharSequence)

            // Map SSL option if present
            if (options.hasOption(ConnectionFactoryOptions.SSL)) {
                val ssl = options.getValue(ConnectionFactoryOptions.SSL) as Boolean
                builder.sslMode(if (ssl) SslMode.REQUIRED else SslMode.DISABLED)
            }

            return MySqlConnectionFactory.from(builder.extendWith(StringRegistrar()).build())
        }

        private class StringCodec : Codec<Any?> {
            override fun canDecode(info: MySqlReadableMetadata, target: Class<*>?): Boolean = true
            override fun decode(
                value: ByteBuf,
                info: MySqlReadableMetadata,
                target: Class<*>?,
                binary: Boolean,
                context: CodecContext
            ): Any {
                val bytes = ByteArray(value.readableBytes())
                value.readBytes(bytes)
                return String(bytes)
            }

            override fun canEncode(value: Any): Boolean = false
            override fun encode(value: Any, context: CodecContext): MySqlParameter {
                error("This codec is only used for decoding.")
            }
        }

        private class StringRegistrar : CodecRegistrar {
            override fun register(allocator: ByteBufAllocator, registry: CodecRegistry) {
                registry.addFirst(StringCodec())
            }
        }

        private fun connectionPoolConfiguration(
            options: ConnectionPool.Options,
            connectionFactory: MySqlConnectionFactory
        ): ConnectionPoolConfiguration {
            return ConnectionPoolConfiguration.builder(connectionFactory).apply {
                options.minConnections?.let { minIdle(it) }
                maxSize(options.maxConnections)
                options.acquireTimeout?.let { maxAcquireTime(it.toJavaDuration()) }
                options.idleTimeout?.let { maxIdleTime(it.toJavaDuration()) }
                options.maxLifetime?.let { maxLifeTime(it.toJavaDuration()) }
            }.build()
        }

        /** Column types that hold raw bytes: r2dbc-mysql tells them from text by the `binary` collation. */
        private val BINARY_TYPES = setOf("VARBINARY", "TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB")

        private fun Row.toRow(): ResultSet.Row {
            val columns = metadata.columnMetadatas.mapIndexed { i, c ->
                if (c.type.name in BINARY_TYPES) {
                    ResultSet.Row.Column(
                        ordinal = i,
                        name = c.name,
                        type = c.type.name,
                        value = null,
                        bytes = get(i, ByteArray::class.java)
                    )
                } else {
                    ResultSet.Row.Column(
                        ordinal = i,
                        name = c.name,
                        type = c.type.name,
                        value = get(i, String::class.java)
                    )
                }
            }
            return ResultSet.Row(columns)
        }

        /**
         * Streams the rows of the statement with reactive backpressure (R2DBC publishes the rows as the
         * server sends them; prepared statements honour the fetch size through a server-side cursor);
         * a driver failure fails the collection with the matching [SQLError].
         */
        private fun io.r2dbc.spi.Statement.streamRows(): Flow<ResultSet.Row> = flow {
            execute().asFlow().collect { result ->
                emitAll(result.map { row, _ -> row.toRow() }.asFlow())
            }
        }.catch { e -> throw (e as? SQLError) ?: e.toSQLError() }

        private suspend fun NativeR2dbcResultSet.toResultSet(): ResultSet {
            val rows = map { r, _ -> r.toRow() }.asFlow().toList()
            val meta = if (rows.isEmpty()) ResultSet.Metadata(emptyList())
            else rows.first().toMetadata()
            return ResultSet(rows, null, meta)
        }
    }
}
