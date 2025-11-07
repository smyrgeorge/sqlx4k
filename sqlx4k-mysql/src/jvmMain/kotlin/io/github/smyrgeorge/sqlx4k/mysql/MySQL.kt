package io.github.smyrgeorge.sqlx4k.mysql

import io.asyncer.r2dbc.mysql.MySqlConnectionConfiguration
import io.asyncer.r2dbc.mysql.MySqlConnectionFactory
import io.asyncer.r2dbc.mysql.MySqlParameter
import io.asyncer.r2dbc.mysql.api.MySqlReadableMetadata
import io.asyncer.r2dbc.mysql.codec.Codec
import io.asyncer.r2dbc.mysql.codec.CodecContext
import io.asyncer.r2dbc.mysql.codec.CodecRegistry
import io.asyncer.r2dbc.mysql.extension.CodecRegistrar
import io.github.smyrgeorge.sqlx4k.*
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migration
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import reactor.pool.PoolAcquireTimeoutException
import reactor.pool.PoolShutdownException
import java.net.URI
import kotlin.jvm.optionals.getOrElse
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import io.r2dbc.pool.ConnectionPool as R2dbcConnectionPool
import io.r2dbc.spi.Connection as R2dbcConnection
import io.r2dbc.spi.Result as R2dbcResultSet

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
    override val encoders: Statement.ValueEncoderRegistry = Statement.ValueEncoderRegistry()
) : IMySQL {
    private val connectionFactory: MySqlConnectionFactory = connectionFactory(url, username, password)
    private val poolConfiguration: ConnectionPoolConfiguration = connectionOptions(options, connectionFactory)
    private val pool: R2dbcConnectionPool = R2dbcConnectionPool(poolConfiguration).apply {
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

    override suspend fun close(): Result<Unit> = runCatching {
        try {
            pool.disposeLater().awaitFirstOrNull()
        } catch (e: Exception) {
            SQLError(SQLError.Code.WorkerCrashed, e.message).ex()
        }
    }

    override fun poolSize(): Int = pool.metrics.getOrElse { error("No metrics available.") }.allocatedSize()
    override fun poolIdleSize(): Int = pool.metrics.getOrElse { error("No metrics available.") }.idleSize()

    override suspend fun acquire(): Result<Connection> = runCatching {
        Cn(pool.acquire(), encoders)
    }

    override suspend fun execute(sql: String) = runCatching {
        @Suppress("SqlSourceToSinkFlow")
        with(pool.acquire()) {
            val res = try {
                createStatement(sql).execute().awaitSingle().rowsUpdated.awaitFirstOrNull() ?: 0
            } catch (e: Exception) {
                SQLError(SQLError.Code.Database, e.message).ex()
            } finally {
                close().awaitFirstOrNull()
            }
            res
        }
    }

    override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
        @Suppress("SqlSourceToSinkFlow")
        with(pool.acquire()) {
            val res = try {
                createStatement(sql).execute().awaitSingle().toResultSet()
            } catch (e: Exception) {
                SQLError(SQLError.Code.Database, e.message).ex()
            } finally {
                close().awaitFirstOrNull()
            }
            res
        }
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        with(pool.acquire()) {
            try {
                beginTransaction().awaitFirstOrNull()
            } catch (e: Exception) {
                close().awaitFirstOrNull()
                SQLError(SQLError.Code.Database, e.message).ex()
            }
            Tx(this, true, encoders)
        }
    }

    private suspend fun R2dbcConnectionPool.acquire(): R2dbcConnection {
        return try {
            create().awaitSingle()
        } catch (e: Exception) {
            when (e) {
                is PoolShutdownException -> SQLError(SQLError.Code.PoolClosed, e.message).ex()
                is PoolAcquireTimeoutException -> SQLError(SQLError.Code.PoolTimedOut, e.message).ex()
                else -> SQLError(SQLError.Code.Pool, e.message).ex()
            }
        }
    }

    class Cn(
        private val connection: R2dbcConnection,
        override val encoders: Statement.ValueEncoderRegistry
    ) : Connection {
        private val mutex = Mutex()
        private var _status: Connection.Status = Connection.Status.Open
        override val status: Connection.Status get() = _status
        private var _transactionIsolationLevel: Transaction.IsolationLevel? = null
        override val transactionIsolationLevel: Transaction.IsolationLevel? get() = _transactionIsolationLevel

        override suspend fun close(): Result<Unit> = runCatching {
            mutex.withLock {
                assertIsOpen()
                _status = Connection.Status.Closed
                connection.close().awaitFirstOrNull()
            }
            transactionIsolationLevel?.let {
                val default = IMySQL.DEFAULT_TRANSACTION_ISOLATION_LEVEL
                setTransactionIsolationLevel(default)
            }
        }

        override suspend fun setTransactionIsolationLevel(level: Transaction.IsolationLevel): Result<Unit> {
            return super.setTransactionIsolationLevel(level).also { _transactionIsolationLevel = level }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                @Suppress("SqlSourceToSinkFlow")
                connection.createStatement(sql).execute().awaitSingle().rowsUpdated.awaitFirstOrNull() ?: 0
            }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                @Suppress("SqlSourceToSinkFlow")
                connection.createStatement(sql).execute().awaitSingle().toResultSet().toResult()
            }
        }

        override suspend fun begin(): Result<Transaction> = runCatching {
            mutex.withLock {
                assertIsOpen()
                try {
                    connection.beginTransaction().awaitFirstOrNull()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message).ex()
                }
                Tx(connection, false, encoders)
            }
        }
    }

    class Tx(
        private var connection: R2dbcConnection,
        private val closeConnectionAfterTx: Boolean,
        override val encoders: Statement.ValueEncoderRegistry
    ) : Transaction {
        private val mutex = Mutex()
        private var _status: Transaction.Status = Transaction.Status.Open
        override val status: Transaction.Status get() = _status

        override suspend fun commit(): Result<Unit> = runCatching {
            mutex.withLock {
                assertIsOpen()
                _status = Transaction.Status.Closed
                try {
                    connection.commitTransaction().awaitFirstOrNull()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message).ex()
                } finally {
                    if (closeConnectionAfterTx) connection.close().awaitFirstOrNull()
                }
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                assertIsOpen()
                _status = Transaction.Status.Closed
                try {
                    connection.rollbackTransaction().awaitFirstOrNull()
                } catch (e: Exception) {
                    SQLError(SQLError.Code.Database, e.message).ex()
                } finally {
                    if (closeConnectionAfterTx) connection.close().awaitFirstOrNull()
                }
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                @Suppress("SqlSourceToSinkFlow")
                connection.createStatement(sql).execute().awaitSingle().rowsUpdated.awaitFirstOrNull() ?: 0
            }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                @Suppress("SqlSourceToSinkFlow")
                connection.createStatement(sql).execute().awaitSingle().toResultSet().toResult()
            }
        }
    }

    companion object {
        private fun connectionFactory(url: String, username: String, password: String): MySqlConnectionFactory {
            val url = URI(url)
            return MySqlConnectionFactory.from(
                MySqlConnectionConfiguration.builder()
                    .host(url.host)
                    .port(url.port.takeIf { it > 0 } ?: 5432)
                    .database(url.path.removePrefix("/"))
                    .username(username)
                    .password(password)
                    .extendWith(StringRegistrar())
                    .build()
            )
        }

        private class StringCodec() : Codec<Any?> {
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

        private fun connectionOptions(
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

        private suspend fun R2dbcResultSet.toResultSet(): ResultSet {
            fun Row.toRow(): ResultSet.Row {
                val columns = metadata.columnMetadatas.mapIndexed { i, c ->
                    ResultSet.Row.Column(
                        ordinal = i,
                        name = c.name,
                        type = c.type.name,
                        value = get(i, String::class.java)
                    )
                }
                return ResultSet.Row(columns)
            }

            val rows = map { r, _ -> r.toRow() }.asFlow().toList()
            val meta = if (rows.isEmpty()) ResultSet.Metadata(emptyList())
            else rows.first().toMetadata()
            return ResultSet(rows, null, meta)
        }
    }
}
