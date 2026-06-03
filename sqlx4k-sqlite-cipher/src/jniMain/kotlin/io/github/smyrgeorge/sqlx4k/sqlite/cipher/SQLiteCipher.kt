@file:Suppress("DuplicatedCode")

package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.Transaction.IsolationLevel
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migration
import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Encrypted SQLite (SQLCipher) driver for the JVM and Android, backed by the Rust `sqlx` core via
 * JNI. The same Rust engine powers the Kotlin/Native targets through FFI, so behavior is uniform.
 *
 * Each operation runs the blocking JNI call on [Dispatchers.IO]. The `url` must already be a
 * resolved `sqlite:` URL — the `sqliteCipher(...)` factory normalizes it per platform (Android
 * resolves relative filenames against the app's private database directory).
 *
 * @param url The (resolved) SQLite connection URL.
 * @param password The SQLCipher passphrase, applied as `PRAGMA key`. Pass an empty string for an
 * unencrypted database.
 * @param options Connection pool configuration.
 * @param encoders Optional registry of value encoders used when binding query parameters.
 */
class SQLiteCipher(
    url: String,
    password: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
    override val encoders: ValueEncoderRegistry = ValueEncoderRegistry()
) : ISQLiteCipher {
    private val rt: Long

    init {
        ensureCipherNativeLoaded()
        rt = decodeResult(
            CipherJni.nativeOf(
                url = url,
                password = password,
                minConnections = options.minConnections ?: -1,
                maxConnections = options.maxConnections,
                acquireTimeoutMillis = options.acquireTimeout?.inWholeMilliseconds?.toInt() ?: -1,
                idleTimeoutMillis = options.idleTimeout?.inWholeMilliseconds?.toInt() ?: -1,
                maxLifetimeMillis = options.maxLifetime?.inWholeMilliseconds?.toInt() ?: -1,
            )
        ).rtOrError()
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
        withContext(Dispatchers.IO) { decodeResult(CipherJni.nativeClose(rt)).throwIfError() }
    }

    override fun poolSize(): Int = CipherJni.nativePoolSize(rt)
    override fun poolIdleSize(): Int = CipherJni.nativePoolIdleSize(rt)

    override suspend fun acquire(): Result<Connection> = runCatching {
        withContext(Dispatchers.IO) {
            val cn = decodeResult(CipherJni.nativeCnAcquire(rt)).cnOrError()
            SqlxConnection(rt, cn, encoders)
        }
    }

    override suspend fun execute(sql: String): Result<Long> = runCatching {
        withContext(Dispatchers.IO) {
            decodeResult(CipherJni.nativeQuery(rt, sql)).rowsAffectedOrError()
        }
    }

    override suspend fun execute(statement: Statement): Result<Long> = runCatching {
        val nq = statement.renderNativeQuery(Dialect.SQLite, encoders)
        withContext(Dispatchers.IO) {
            decodeResult(CipherJni.nativeQueryWithParams(rt, nq.sql, encodeParams(nq.values)))
                .rowsAffectedOrError()
        }
    }

    override suspend fun fetchAll(sql: String): Result<ResultSet> {
        val rs = withContext(Dispatchers.IO) {
            decodeResult(CipherJni.nativeFetchAll(rt, sql)).toResultSet()
        }
        return rs.toResult()
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> {
        val nq = runCatching { statement.renderNativeQuery(Dialect.SQLite, encoders) }
            .getOrElse { return Result.failure(it) }
        val rs = withContext(Dispatchers.IO) {
            decodeResult(CipherJni.nativeFetchAllWithParams(rt, nq.sql, encodeParams(nq.values))).toResultSet()
        }
        return rs.toResult()
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        withContext(Dispatchers.IO) {
            val tx = decodeResult(CipherJni.nativeTxBegin(rt)).txOrError()
            SqlxTransaction(rt, tx, encoders)
        }
    }

    class SqlxConnection(
        private val rt: Long,
        private val cn: Long,
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
                withContext(Dispatchers.IO) { decodeResult(CipherJni.nativeCnRelease(rt, cn)).throwIfError() }
            }
        }

        override suspend fun setTransactionIsolationLevel(level: IsolationLevel): Result<Unit> {
            // SQLite does not support setting the transaction isolation level.
            return Result.success(Unit)
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    decodeResult(CipherJni.nativeCnQuery(rt, cn, sql)).rowsAffectedOrError()
                }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> = runCatching {
            val nq = statement.renderNativeQuery(Dialect.SQLite, encoders)
            mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    decodeResult(CipherJni.nativeCnQueryWithParams(rt, cn, nq.sql, encodeParams(nq.values)))
                        .rowsAffectedOrError()
                }
            }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    decodeResult(CipherJni.nativeCnFetchAll(rt, cn, sql)).toResultSet()
                }.toResult().getOrThrow()
            }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> {
            val nq = runCatching { statement.renderNativeQuery(Dialect.SQLite, encoders) }
                .getOrElse { return Result.failure(it) }
            return runCatching {
                mutex.withLock {
                    assertIsOpen()
                    withContext(Dispatchers.IO) {
                        decodeResult(CipherJni.nativeCnFetchAllWithParams(rt, cn, nq.sql, encodeParams(nq.values)))
                            .toResultSet()
                    }.toResult().getOrThrow()
                }
            }
        }

        override suspend fun begin(): Result<Transaction> = runCatching {
            mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    val tx = decodeResult(CipherJni.nativeCnTxBegin(rt, cn)).txOrError()
                    SqlxTransaction(rt, tx, encoders)
                }
            }
        }
    }

    class SqlxTransaction(
        private val rt: Long,
        private var tx: Long,
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
                withContext(Dispatchers.IO) { decodeResult(CipherJni.nativeTxCommit(rt, tx)).throwIfError() }
                _commited = true
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                if (rollbacked) return@withLock
                assertIsOpen()
                _status = Transaction.Status.Closed
                withContext(Dispatchers.IO) { decodeResult(CipherJni.nativeTxRollback(rt, tx)).throwIfError() }
                _rollbacked = true
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    val result = decodeResult(CipherJni.nativeTxQuery(rt, tx, sql))
                    if (result.tx != 0L) tx = result.tx
                    result.rowsAffectedOrError()
                }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> = runCatching {
            val nq = statement.renderNativeQuery(Dialect.SQLite, encoders)
            mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    val result = decodeResult(
                        CipherJni.nativeTxQueryWithParams(rt, tx, nq.sql, encodeParams(nq.values))
                    )
                    if (result.tx != 0L) tx = result.tx
                    result.rowsAffectedOrError()
                }
            }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    val result = decodeResult(CipherJni.nativeTxFetchAll(rt, tx, sql))
                    if (result.tx != 0L) tx = result.tx
                    result.toResultSet()
                }.toResult().getOrThrow()
            }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> {
            val nq = runCatching { statement.renderNativeQuery(Dialect.SQLite, encoders) }
                .getOrElse { return Result.failure(it) }
            return runCatching {
                mutex.withLock {
                    assertIsOpen()
                    withContext(Dispatchers.IO) {
                        val result = decodeResult(
                            CipherJni.nativeTxFetchAllWithParams(rt, tx, nq.sql, encodeParams(nq.values))
                        )
                        if (result.tx != 0L) tx = result.tx
                        result.toResultSet()
                    }.toResult().getOrThrow()
                }
            }
        }
    }
}
