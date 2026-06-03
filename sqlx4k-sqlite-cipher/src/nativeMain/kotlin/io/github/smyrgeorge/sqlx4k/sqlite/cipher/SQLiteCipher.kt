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
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_close
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_cn_acquire
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_cn_fetch_all
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_cn_fetch_all_with_params
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_cn_query
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_cn_query_with_params
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_cn_release
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_cn_tx_begin
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_fetch_all
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_fetch_all_with_params
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_of
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_pool_idle_size
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_pool_size
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_query
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_query_with_params
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_tx_begin
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_tx_commit
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_tx_fetch_all
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_tx_fetch_all_with_params
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_tx_query
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_tx_query_with_params
import sqlx4k.sqlite.cipher.sqlx4k_sqlite_cipher_tx_rollback

/**
 * Encrypted SQLite (SQLCipher) driver for Kotlin/Native targets, backed by the Rust `sqlx` core
 * via cinterop FFI. Provides connection pooling and transactional support in a coroutine-based
 * environment.
 *
 * `sqlite::memory:` | Open an in-memory database.
 * `sqlite:data.db` | Open the file `data.db` in the current directory.
 * `sqlite://data.db` | Open the file `data.db` in the current directory.
 * `sqlite:///data.db` | Open the file `data.db` from the root (`/`) directory.
 * `sqlite://data.db?mode=ro` | Open the file `data.db` for read-only access.
 *
 * @param url The URL string for connecting to the SQLite database.
 * @param password The SQLCipher passphrase, applied as `PRAGMA key`. Pass an empty string for an
 * unencrypted database.
 * @param options Configuration options for the connection pool.
 * @param encoders Optional registry of value encoders to use for encoding query parameters.
 */
@OptIn(ExperimentalForeignApi::class)
class SQLiteCipher(
    url: String,
    password: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
    override val encoders: ValueEncoderRegistry = ValueEncoderRegistry()
) : ISQLiteCipher {
    private val rt: CPointer<out CPointed> = sqlx4k_sqlite_cipher_of(
        url = url,
        password = password,
        min_connections = options.minConnections ?: -1,
        max_connections = options.maxConnections,
        acquire_timeout_milis = options.acquireTimeout?.inWholeMilliseconds?.toInt() ?: -1,
        idle_timeout_milis = options.idleTimeout?.inWholeMilliseconds?.toInt() ?: -1,
        max_lifetime_milis = options.maxLifetime?.inWholeMilliseconds?.toInt() ?: -1,
    ).rtOrError()

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
        sqlx { c -> sqlx4k_sqlite_cipher_close(rt, c, fn) }.throwIfError()
    }

    override fun poolSize(): Int = sqlx4k_sqlite_cipher_pool_size(rt)
    override fun poolIdleSize(): Int = sqlx4k_sqlite_cipher_pool_idle_size(rt)

    override suspend fun acquire(): Result<Connection> = runCatching {
        sqlx { c -> sqlx4k_sqlite_cipher_cn_acquire(rt, c, fn) }.use {
            it.throwIfError()
            SqlxConnection(rt, it.cn!!, encoders)
        }
    }

    override suspend fun execute(sql: String): Result<Long> = runCatching {
        sqlx { c -> sqlx4k_sqlite_cipher_query(rt, sql, c, fn) }.rowsAffectedOrError()
    }

    override suspend fun execute(statement: Statement): Result<Long> = runCatching {
        val nq = statement.renderNativeQuery(Dialect.SQLite, encoders)
        memScoped {
            val (paramsPtr, paramsLen) = allocParams(nq.values)
            sqlx { c -> sqlx4k_sqlite_cipher_query_with_params(rt, nq.sql, paramsPtr, paramsLen, c, fn) }
                .rowsAffectedOrError()
        }
    }

    override suspend fun fetchAll(sql: String): Result<ResultSet> {
        val res = sqlx { c -> sqlx4k_sqlite_cipher_fetch_all(rt, sql, c, fn) }
        return res.use { it.toResultSet() }.toResult()
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> {
        val nq = runCatching { statement.renderNativeQuery(Dialect.SQLite, encoders) }
            .getOrElse { return Result.failure(it) }
        return memScoped {
            val (paramsPtr, paramsLen) = allocParams(nq.values)
            val res = sqlx { c ->
                sqlx4k_sqlite_cipher_fetch_all_with_params(rt, nq.sql, paramsPtr, paramsLen, c, fn)
            }
            res.use { it.toResultSet() }.toResult()
        }
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        sqlx { c -> sqlx4k_sqlite_cipher_tx_begin(rt, c, fn) }.use {
            it.throwIfError()
            SqlxTransaction(rt, it.tx!!, encoders)
        }
    }

    class SqlxConnection(
        private val rt: CPointer<out CPointed>,
        private val cn: CPointer<out CPointed>,
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
                sqlx { c -> sqlx4k_sqlite_cipher_cn_release(rt, cn, c, fn) }.throwIfError()
            }
        }

        override suspend fun setTransactionIsolationLevel(level: IsolationLevel): Result<Unit> {
            // SQLite does not support setting the transaction isolation level.
            return Result.success(Unit)
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                sqlx { c -> sqlx4k_sqlite_cipher_cn_query(rt, cn, sql, c, fn) }.use {
                    it.throwIfError()
                    it.rows_affected.toLong()
                }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> = runCatching {
            val nq = statement.renderNativeQuery(Dialect.SQLite, encoders)
            mutex.withLock {
                assertIsOpen()
                memScoped {
                    val (paramsPtr, paramsLen) = allocParams(nq.values)
                    sqlx { c ->
                        sqlx4k_sqlite_cipher_cn_query_with_params(rt, cn, nq.sql, paramsPtr, paramsLen, c, fn)
                    }.use {
                        it.throwIfError()
                        it.rows_affected.toLong()
                    }
                }
            }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                sqlx { c -> sqlx4k_sqlite_cipher_cn_fetch_all(rt, cn, sql, c, fn) }
                    .use { it.toResultSet() }
                    .toResult()
            }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> {
            val nq = runCatching { statement.renderNativeQuery(Dialect.SQLite, encoders) }
                .getOrElse { return Result.failure(it) }
            return mutex.withLock {
                assertIsOpen()
                memScoped {
                    val (paramsPtr, paramsLen) = allocParams(nq.values)
                    sqlx { c ->
                        sqlx4k_sqlite_cipher_cn_fetch_all_with_params(rt, cn, nq.sql, paramsPtr, paramsLen, c, fn)
                    }.use { it.toResultSet() }.toResult()
                }
            }
        }

        override suspend fun begin(): Result<Transaction> = runCatching {
            mutex.withLock {
                assertIsOpen()
                sqlx { c -> sqlx4k_sqlite_cipher_cn_tx_begin(rt, cn, c, fn) }.use {
                    it.throwIfError()
                    SqlxTransaction(rt, it.tx!!, encoders)
                }
            }
        }
    }

    class SqlxTransaction(
        private val rt: CPointer<out CPointed>,
        private var tx: CPointer<out CPointed>,
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
                sqlx { c -> sqlx4k_sqlite_cipher_tx_commit(rt, tx, c, fn) }.throwIfError()
                _commited = true
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            mutex.withLock {
                if (rollbacked) return@withLock
                assertIsOpen()
                _status = Transaction.Status.Closed
                sqlx { c -> sqlx4k_sqlite_cipher_tx_rollback(rt, tx, c, fn) }.throwIfError()
                _rollbacked = true
            }
        }

        override suspend fun execute(sql: String): Result<Long> = runCatching {
            mutex.withLock {
                assertIsOpen()
                sqlx { c -> sqlx4k_sqlite_cipher_tx_query(rt, tx, sql, c, fn) }.use {
                    tx = it.tx!!
                    it.throwIfError()
                    it.rows_affected.toLong()
                }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> = runCatching {
            val nq = statement.renderNativeQuery(Dialect.SQLite, encoders)
            mutex.withLock {
                assertIsOpen()
                memScoped {
                    val (paramsPtr, paramsLen) = allocParams(nq.values)
                    sqlx { c ->
                        sqlx4k_sqlite_cipher_tx_query_with_params(rt, tx, nq.sql, paramsPtr, paramsLen, c, fn)
                    }.use {
                        tx = it.tx!!
                        it.throwIfError()
                        it.rows_affected.toLong()
                    }
                }
            }
        }

        override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
            return mutex.withLock {
                assertIsOpen()
                sqlx { c -> sqlx4k_sqlite_cipher_tx_fetch_all(rt, tx, sql, c, fn) }.use {
                    tx = it.tx!!
                    it.toResultSet()
                }.toResult()
            }
        }

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> {
            val nq = runCatching { statement.renderNativeQuery(Dialect.SQLite, encoders) }
                .getOrElse { return Result.failure(it) }
            return mutex.withLock {
                assertIsOpen()
                memScoped {
                    val (paramsPtr, paramsLen) = allocParams(nq.values)
                    sqlx { c ->
                        sqlx4k_sqlite_cipher_tx_fetch_all_with_params(rt, tx, nq.sql, paramsPtr, paramsLen, c, fn)
                    }.use {
                        tx = it.tx!!
                        it.toResultSet()
                    }.toResult()
                }
            }
        }
    }
}
