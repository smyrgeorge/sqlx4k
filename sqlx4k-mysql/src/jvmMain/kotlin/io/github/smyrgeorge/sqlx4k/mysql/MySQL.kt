@file:Suppress("SqlSourceToSinkFlow", "SqlNoDataSourceInspection")

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
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Row
import java.net.URLDecoder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
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
import reactor.core.publisher.Mono
import kotlin.jvm.optionals.getOrElse
import kotlin.text.Charsets.UTF_8
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid
import io.r2dbc.pool.ConnectionPool as NativeR2dbcConnectionPool
import io.r2dbc.spi.Connection as NativeR2dbcConnection
import io.r2dbc.spi.Result as NativeR2dbcResultSet

/**
 * The `MySQL` class provides a driver implementation for interacting with a MySQL database.
 * It supports connection pooling, transactional operations, and executing SQL queries.
 *
 *  The connection URL should follow the nex pattern,
 *  as described by [MySQL](https://dev.mysql.com/doc/connector-j/8.0/en/connector-j-reference-jdbc-url-format.html).
 *
 *  The generic format of the connection URL:
 *  mysql://[host][:port][/database][?properties]
 *
 * The port defaults to 3306. The properties are the ones the native driver (sqlx) reads: `ssl-mode`
 * (`disabled`, `preferred`, `required`, `verify_ca`, `verify_identity`), `ssl-ca`, `ssl-cert`, `ssl-key`,
 * `socket` and `timezone` (the session time zone, UTC by default), as well as r2dbc's `ssl=true|false`.
 * `charset`, `collation` and `statement-cache-capacity` are accepted and have no effect on the JVM. Any
 * other property is rejected.
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

    override suspend fun executeWithResult(sql: String): Result<ExecuteResult> =
        pool.withConnection { createStatement(sql).executeForResult() }

    override suspend fun fetchAll(sql: String): Result<ResultSet> =
        pool.withConnection { createStatement(sql).fetchResultSet() }

    override suspend fun execute(statement: Statement): Result<Long> =
        executeWithResult(statement).map { it.rowsAffected }

    override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> =
        pool.withConnection { createStatement(statement, encoders).executeForResult() }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        pool.withConnection { createStatement(statement, encoders).fetchResultSet() }

    override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> =
        pool.flowWithConnection { createStatement(sql).fetchSize(fetchSize).streamRows() }

    override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
        pool.flowWithConnection { createStatement(statement, encoders).fetchSize(fetchSize).streamRows() }

    override suspend fun begin(): Result<Transaction> = runCatching {
        with(pool.acquire()) {
            try {
                beginTransaction().toMono().awaitSingleOrNull()
            } catch (e: Exception) {
                withContext(NonCancellable) { close().toMono().awaitSingleOrNull() }
                e.toSQLError().raise()
            }
            R2dbcTransaction(this, true, encoders)
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

        // Runs to completion also when the caller is cancelled: in a cancelled coroutine the release would be
        // cancelled before reaching the pool, and the connection would stay checked out for good.
        override suspend fun close(): Result<Unit> = runCatching {
            withContext(NonCancellable) {
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

        private suspend fun executeWithResult(sql: String, lock: Boolean): Result<ExecuteResult> =
            if (lock) mutex.withLockCatching(::assertIsOpen) { connection.createStatement(sql).executeForResult() }
            else runCatching { rethrowAsSQLError { connection.createStatement(sql).executeForResult() } }

        override suspend fun execute(sql: String): Result<Long> = execute(sql, true)

        override suspend fun executeWithResult(sql: String): Result<ExecuteResult> = executeWithResult(sql, true)

        override suspend fun fetchAll(sql: String): Result<ResultSet> = mutex.withLockCatching(::assertIsOpen) {
            connection.createStatement(sql).fetchResultSet()
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            executeWithResult(statement).map { it.rowsAffected }

        override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) {
                connection.createStatement(statement, encoders).executeForResult()
            }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            mutex.withLockCatching(::assertIsOpen) {
                connection.createStatement(statement, encoders).fetchResultSet()
            }

        // The connection is held (its mutex locked) for the whole collection.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) { connection.createStatement(sql).fetchSize(fetchSize).streamRows() }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) {
                connection.createStatement(statement, encoders).fetchSize(fetchSize).streamRows()
            }

        override suspend fun begin(): Result<Transaction> = mutex.withLockCatching(::assertIsOpen) {
            connection.beginTransaction().toMono().awaitSingleOrNull()
            R2dbcTransaction(connection, false, encoders)
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
                connection.endTransaction(closeConnectionAfterTx) {
                    commitTransaction().toMono().awaitSingleOrNull()
                    _commited = true
                }
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                if (rollbacked) return@withLock
                assertIsOpen()
                _status = Transaction.Status.Closed
                connection.endTransaction(closeConnectionAfterTx) {
                    rollbackTransaction().toMono().awaitSingleOrNull()
                    _rollbacked = true
                }
            }
        }

        override suspend fun execute(sql: String): Result<Long> = executeWithResult(sql).map { it.rowsAffected }

        override suspend fun executeWithResult(sql: String): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) { connection.createStatement(sql).executeForResult() }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = mutex.withLockCatching(::assertIsOpen) {
            connection.createStatement(sql).fetchResultSet()
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            executeWithResult(statement).map { it.rowsAffected }

        override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> =
            mutex.withLockCatching(::assertIsOpen) {
                connection.createStatement(statement, encoders).executeForResult()
            }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            mutex.withLockCatching(::assertIsOpen) {
                connection.createStatement(statement, encoders).fetchResultSet()
            }

        // The transaction is held (its mutex locked) for the whole collection.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) { connection.createStatement(sql).fetchSize(fetchSize).streamRows() }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> =
            mutex.flowWithLock(::assertIsOpen) {
                connection.createStatement(statement, encoders).fetchSize(fetchSize).streamRows()
            }
    }

    companion object {
        /**
         * Executes the statement and reports its update count and generated id. With returnGeneratedValues(),
         * r2dbc-mysql reports `LAST_INSERT_ID()` from the server's reply to the statement (no extra query); it is 0
         * when the statement generated no id. Every result is consumed: see fetchResultSet.
         */
        private suspend fun io.r2dbc.spi.Statement.executeForResult(): ExecuteResult {
            val segments = mutableListOf<Pair<Long, Long?>>()
            returnGeneratedValues().execute().asFlow().collect { result ->
                segments += result
                    .flatMap { segment ->
                        // An error arrives as a message segment: fail like rowsUpdated would.
                        if (segment is NativeR2dbcResultSet.Message) return@flatMap Mono.error(segment.exception())
                        // A segment may carry both: the update count and the generated id.
                        val count = (segment as? NativeR2dbcResultSet.UpdateCount)?.value() ?: 0L
                        val id = (segment as? NativeR2dbcResultSet.RowSegment)?.row()?.get(0, Long::class.java)
                        Mono.just(count to id)
                    }
                    .asFlow().toList()
            }
            return ExecuteResult(
                rowsAffected = segments.sumOf { it.first },
                lastInsertId = segments.firstNotNullOfOrNull { it.second }?.takeIf { it != 0L },
            )
        }

        /**
         * Executes the statement and reads the rows of its last result. Every result is consumed, in order, as it
         * arrives: a statement can produce several (PostgreSQL's SET produces two), and a result left unconsumed
         * (awaitLast() only kept the last one) left the connection out of step, so the error of the next statement
         * on it was lost and reported as a success.
         */
        private suspend fun io.r2dbc.spi.Statement.fetchResultSet(): ResultSet {
            var last: ResultSet? = null
            execute().asFlow().collect { result -> last = result.toResultSet() }
            return last ?: ResultSet(emptyList(), null, ResultSet.Metadata(emptyList()))
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

        // The URL properties the native driver (sqlx) reads, and r2dbc's `ssl`.
        private val URL_PARAMS = setOf(
            "ssl", "ssl-mode", "sslmode", "ssl-ca", "sslca", "ssl-cert", "sslcert", "ssl-key", "sslkey", "socket",
            "timezone", "time-zone", "charset", "collation", "statement-cache-capacity",
        )
        private val SSL_MODES = listOf(
            SslMode.DISABLED, SslMode.PREFERRED, SslMode.REQUIRED, SslMode.VERIFY_CA, SslMode.VERIFY_IDENTITY
        ).associateBy { it.name.lowercase() }

        private fun invalidUrl(message: String): Nothing = SQLError(SQLError.Code.Pool, message).raise()

        private fun connectionFactory(url: String, username: String, password: String): MySqlConnectionFactory {
            val url = if (!url.startsWith("r2dbc:")) "r2dbc:$url" else url
            val options = ConnectionFactoryOptions
                .builder()
                .from(ConnectionFactoryOptions.parse(url))
                .option(ConnectionFactoryOptions.USER, username)
                .option(ConnectionFactoryOptions.PASSWORD, password)
                .build()

            // An unknown property is rejected rather than ignored: a misspelt `ssl-mode` would silently connect
            // without the TLS verification it asked for.
            val params = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), UTF_8)
            }
            params.keys.firstOrNull { it !in URL_PARAMS }?.let {
                invalidUrl("Unknown parameter `$it` in MySQL URL. Supported: ${URL_PARAMS.joinToString()}.")
            }
            fun param(vararg names: String): String? = names.firstNotNullOfOrNull { params[it] }

            // Instants are bound and read as UTC: the session uses UTC too (unless `timezone` says otherwise), as on
            // the native driver (sqlx), or TIMESTAMP columns and NOW() are shifted by the server's time zone.
            val timeZone = param("timezone", "time-zone") ?: "+00:00"
            val builder = MySqlConnectionConfiguration.builder()
                .host(options.getRequiredValue(ConnectionFactoryOptions.HOST) as String)
                .port(options.getValue(ConnectionFactoryOptions.PORT) as? Int ?: 3306)
                .username(options.getRequiredValue(ConnectionFactoryOptions.USER) as String)
                .password(options.getValue(ConnectionFactoryOptions.PASSWORD) as CharSequence)
                .connectionTimeZone("UTC")
                .sessionVariables("time_zone='${timeZone.replace("'", "''")}'")
            (options.getValue(ConnectionFactoryOptions.DATABASE) as? String)?.let { builder.database(it) }
            param("socket")?.let { builder.unixSocket(it) }

            // TLS: `ssl-mode`, or r2dbc's `ssl` (a query value is text; `r2dbcs:` sets it as a Boolean).
            val sslMode = param("ssl-mode", "sslmode")?.let {
                SSL_MODES[it.lowercase()] ?: invalidUrl("Unknown value '$it' for `ssl-mode` in MySQL URL.")
            } ?: options.getValue(ConnectionFactoryOptions.SSL)?.let { ssl ->
                if (ssl.toString().toBoolean()) SslMode.REQUIRED else SslMode.DISABLED
            }
            sslMode?.let { builder.sslMode(it) }
            param("ssl-ca", "sslca")?.let { builder.sslCa(it) }
            param("ssl-cert", "sslcert")?.let { builder.sslCert(it) }
            param("ssl-key", "sslkey")?.let { builder.sslKey(it) }

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
