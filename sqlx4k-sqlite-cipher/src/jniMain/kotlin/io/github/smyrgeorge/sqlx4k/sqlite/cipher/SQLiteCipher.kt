@file:Suppress("DuplicatedCode")

package io.github.smyrgeorge.sqlx4k.sqlite.cipher

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    // One permit per connection, taken (suspended) before a call that waits for a connection of the pool on an IO
    // thread, and given back once it no longer holds the connection. Without it, enough callers blocked waiting would
    // take up every IO thread, and the connections' holders could no longer run a statement or commit to give them
    // back: every caller then waited for the acquire timeout.
    private val permits = Semaphore(options.maxConnections)

    // Without an acquire timeout, the native pool waits for a connection for sqlx's default of 30 seconds: waiting
    // for a permit is bounded the same, so a caller on an exhausted pool (e.g. one querying the pool from inside a
    // transaction on a pool of 1) fails with PoolTimedOut instead of waiting forever.
    private val acquireTimeout: Duration = options.acquireTimeout ?: 30.seconds

    // Whether the permit was taken is recorded in the block: withTimeoutOrNull returns null when the timeout fires
    // after acquire() returned, and a permit taken but then reported as timed out (or cancelled) would never be given
    // back, the pool losing one of its connections for good.
    private suspend fun takePermit() {
        var taken = false
        try {
            withTimeoutOrNull(acquireTimeout) {
                permits.acquire()
                taken = true
            }
        } catch (e: CancellationException) {
            if (taken) permits.release()
            throw e
        }
        if (!taken) SQLError(SQLError.Code.PoolTimedOut, "Timed out waiting for a connection").raise()
    }

    private suspend inline fun <T> withPermit(block: () -> T): T {
        takePermit()
        try {
            return block()
        } finally {
            permits.release()
        }
    }

    init {
        requireValidPassphrase(password)
        ensureCipherNativeLoaded()
        rt = decodeResult(
            CipherJni.nativeOf(
                url = url,
                password = password,
                minConnections = options.minConnections ?: -1,
                maxConnections = options.maxConnections,
                // Capped to the Int the native side takes (about 24.8 days): a longer one wrapped around, to a negative
                // number read there as unset (sqlx's default, e.g. a 30-second acquire timeout) or to a much shorter one.
                acquireTimeoutMillis = options.acquireTimeout?.inWholeMilliseconds?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: -1,
                idleTimeoutMillis = options.idleTimeout?.inWholeMilliseconds?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: -1,
                maxLifetimeMillis = options.maxLifetime?.inWholeMilliseconds?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: -1,
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
        takePermit()
        val cn = try {
            withNativeResource({ decodeResult(CipherJni.nativeCnAcquire(rt)).cnOrError() }) {
                decodeResult(CipherJni.nativeCnRelease(rt, it)).throwIfError()
            }
        } catch (e: Throwable) {
            permits.release()
            throw e
        }
        SqlxConnection(rt, cn, encoders, onClose = permits::release)
    }

    override suspend fun execute(sql: String): Result<Long> =
        executeWithResult(sql).map { it.rowsAffected }

    override suspend fun executeWithResult(sql: String): Result<ExecuteResult> = runCatching {
        withPermit {
            withContext(Dispatchers.IO) {
                decodeResult(CipherJni.nativeQuery(rt, sql)).executeResultOrError(sql)
            }
        }
    }

    override suspend fun execute(statement: Statement): Result<Long> =
        executeWithResult(statement).map { it.rowsAffected }

    override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> = runCatching {
        val nq = statement.renderNativeQuery(Dialect.SQLite, encoders)
        withPermit {
            withContext(Dispatchers.IO) {
                decodeResult(CipherJni.nativeQueryWithParams(rt, nq.sql, encodeParams(nq.values)))
                    .executeResultOrError(nq.sql)
            }
        }
    }

    override suspend fun fetchAll(sql: String): Result<ResultSet> {
        val rs = runCatching {
            withPermit {
                withContext(Dispatchers.IO) {
                    decodeResult(
                        CipherJni.nativeFetchAll(
                            rt,
                            sql
                        )
                    )
                }
            }
        }
            .getOrElse { return Result.failure(it) }
            .toResultSet()
        return rs.toResult()
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> {
        val nq = runCatching { statement.renderNativeQuery(Dialect.SQLite, encoders) }
            .getOrElse { return Result.failure(it) }
        val rs = runCatching {
            withPermit {
                withContext(Dispatchers.IO) {
                    decodeResult(CipherJni.nativeFetchAllWithParams(rt, nq.sql, encodeParams(nq.values)))
                }
            }
        }.getOrElse { return Result.failure(it) }.toResultSet()
        return rs.toResult()
    }

    // A stream holds its connection (and so a permit) for the whole collection.
    override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = flow {
        withPermit {
            emitAll(streamFlow(rt) {
                decodeResult(CipherJni.nativeStreamOpen(rt, sql, encodeParams(emptyList()), fetchSize))
            })
        }
    }

    override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> = flow {
        withPermit {
            emitAll(streamFlow(rt) {
                val nq = statement.renderNativeQuery(Dialect.SQLite, encoders)
                decodeResult(CipherJni.nativeStreamOpen(rt, nq.sql, encodeParams(nq.values), fetchSize))
            })
        }
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        takePermit()
        val tx = try {
            withNativeResource({ decodeResult(CipherJni.nativeTxBegin(rt)).txOrError() }) {
                decodeResult(CipherJni.nativeTxRollback(rt, it)).throwIfError()
            }
        } catch (e: Throwable) {
            permits.release()
            throw e
        }
        SqlxTransaction(rt, tx, encoders, onEnd = permits::release)
    }

    class SqlxConnection(
        private val rt: Long,
        private val cn: Long,
        override val encoders: ValueEncoderRegistry,
        // Gives the pool permit back, once the connection is released.
        private val onClose: () -> Unit = {},
    ) : Connection {
        private val mutex = Mutex()

        // The transactions begun on this connection (a begin() while one is open nests in it, as a savepoint): they
        // share the mutex (all drive the same native connection), and close() rolls back the ones still open.
        private val transactions = mutableListOf<SqlxTransaction>()
        private var _status: Connection.Status = Connection.Status.Open
        override val status: Connection.Status get() = _status
        override val transactionIsolationLevel: IsolationLevel? = null

        // Runs to completion also when the caller is cancelled, so the connection always goes back to the pool.
        override suspend fun close(): Result<Unit> = runCatching {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (status == Connection.Status.Closed) return@withLock
                    _status = Connection.Status.Closed
                    // An open transaction would keep the connection inside it once back in the pool, and its native
                    // handle would outlive the connection it borrows: roll them all back first, innermost first.
                    transactions.asReversed().forEach { runCatching { it.rollbackIfOpenLocked() } }
                    transactions.clear()
                    try {
                        withContext(Dispatchers.IO) { decodeResult(CipherJni.nativeCnRelease(rt, cn)).throwIfError() }
                    } finally {
                        onClose()
                    }
                }
            }
        }

        override suspend fun setTransactionIsolationLevel(level: IsolationLevel): Result<Unit> {
            // SQLite does not support setting the transaction isolation level.
            return Result.success(Unit)
        }

        override suspend fun execute(sql: String): Result<Long> =
            executeWithResult(sql).map { it.rowsAffected }

        override suspend fun executeWithResult(sql: String): Result<ExecuteResult> = runCatching {
            mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    decodeResult(CipherJni.nativeCnQuery(rt, cn, sql)).executeResultOrError(sql)
                }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            executeWithResult(statement).map { it.rowsAffected }

        override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> = runCatching {
            val nq = statement.renderNativeQuery(Dialect.SQLite, encoders)
            mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    decodeResult(CipherJni.nativeCnQueryWithParams(rt, cn, nq.sql, encodeParams(nq.values)))
                        .executeResultOrError(nq.sql)
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

        // The connection is held (its mutex locked) for the whole collection: a stream keeps the
        // connection busy, exactly as it does in sqlx.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = flow {
            mutex.withLock {
                assertIsOpen()
                emitAll(streamFlow(rt) {
                    decodeResult(CipherJni.nativeCnStreamOpen(rt, cn, sql, encodeParams(emptyList()), fetchSize))
                })
            }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> = flow {
            mutex.withLock {
                assertIsOpen()
                emitAll(streamFlow(rt) {
                    val nq = statement.renderNativeQuery(Dialect.SQLite, encoders)
                    decodeResult(CipherJni.nativeCnStreamOpen(rt, cn, nq.sql, encodeParams(nq.values), fetchSize))
                })
            }
        }

        override suspend fun begin(): Result<Transaction> = runCatching {
            mutex.withLock {
                assertIsOpen()
                val tx = withNativeResource({ decodeResult(CipherJni.nativeCnTxBegin(rt, cn)).txOrError() }) {
                    decodeResult(CipherJni.nativeTxRollback(rt, it)).throwIfError()
                }
                SqlxTransaction(rt, tx, encoders, mutex).also { tx ->
                    transactions.removeAll { it.status != Transaction.Status.Open }
                    transactions += tx
                }
            }
        }
    }

    class SqlxTransaction internal constructor(
        private val rt: Long,
        private var tx: Long,
        override val encoders: ValueEncoderRegistry,
        // Shared with the connection the transaction was begun on, which drives the same native connection.
        private val mutex: Mutex,
        // A pool-level transaction gives its pool permit back once it is ended (committed or rolled back).
        private val onEnd: () -> Unit = {},
    ) : Transaction {
        constructor(rt: Long, tx: Long, encoders: ValueEncoderRegistry, onEnd: () -> Unit = {}) :
                this(rt, tx, encoders, Mutex(), onEnd)

        private var _status: Transaction.Status = Transaction.Status.Open
        private var _commited: Boolean = false
        private var _rollbacked: Boolean = false
        override val status: Transaction.Status get() = _status
        override val commited: Boolean get() = _commited
        override val rollbacked: Boolean get() = _rollbacked

        // Commit and rollback end the transaction and free its native handle (and, for a pool-level
        // transaction, its connection): they run to completion also when the caller is cancelled.
        override suspend fun commit(): Result<Unit> = runCatching {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (commited) return@withLock
                    assertIsOpen()
                    _status = Transaction.Status.Closed
                    try {
                        withContext(Dispatchers.IO) { decodeResult(CipherJni.nativeTxCommit(rt, tx)).throwIfError() }
                    } finally {
                        onEnd()
                    }
                    _commited = true
                }
            }
        }

        override suspend fun rollback(): Result<Unit> = runCatching {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (rollbacked) return@withLock
                    assertIsOpen()
                    _status = Transaction.Status.Closed
                    try {
                        withContext(Dispatchers.IO) { decodeResult(CipherJni.nativeTxRollback(rt, tx)).throwIfError() }
                    } finally {
                        onEnd()
                    }
                    _rollbacked = true
                }
            }
        }

        /**
         * Rolls the transaction back if it is still open: called by the connection it was begun on when that is
         * closed, with the shared mutex already held.
         */
        internal suspend fun rollbackIfOpenLocked() {
            if (status != Transaction.Status.Open) return
            _status = Transaction.Status.Closed
            withContext(Dispatchers.IO) { decodeResult(CipherJni.nativeTxRollback(rt, tx)).throwIfError() }
            _rollbacked = true
        }

        override suspend fun execute(sql: String): Result<Long> =
            executeWithResult(sql).map { it.rowsAffected }

        override suspend fun executeWithResult(sql: String): Result<ExecuteResult> = runCatching {
            mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    val result = decodeResult(CipherJni.nativeTxQuery(rt, tx, sql))
                    if (result.tx != 0L) tx = result.tx
                    result.executeResultOrError(sql)
                }
            }
        }

        override suspend fun execute(statement: Statement): Result<Long> =
            executeWithResult(statement).map { it.rowsAffected }

        override suspend fun executeWithResult(statement: Statement): Result<ExecuteResult> = runCatching {
            val nq = statement.renderNativeQuery(Dialect.SQLite, encoders)
            mutex.withLock {
                assertIsOpen()
                withContext(Dispatchers.IO) {
                    val result = decodeResult(
                        CipherJni.nativeTxQueryWithParams(rt, tx, nq.sql, encodeParams(nq.values))
                    )
                    if (result.tx != 0L) tx = result.tx
                    result.executeResultOrError(nq.sql)
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

        // The transaction is held (its mutex locked) for the whole collection; the stream borrows the
        // transaction in place, so the `tx` handle is unchanged afterwards.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = flow {
            mutex.withLock {
                assertIsOpen()
                emitAll(streamFlow(rt) {
                    decodeResult(CipherJni.nativeTxStreamOpen(rt, tx, sql, encodeParams(emptyList()), fetchSize))
                })
            }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> = flow {
            mutex.withLock {
                assertIsOpen()
                emitAll(streamFlow(rt) {
                    val nq = statement.renderNativeQuery(Dialect.SQLite, encoders)
                    decodeResult(CipherJni.nativeTxStreamOpen(rt, tx, nq.sql, encodeParams(nq.values), fetchSize))
                })
            }
        }
    }
}
