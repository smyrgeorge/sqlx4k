package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.ExecuteResult
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.types.TypedNull
import io.r2dbc.spi.R2dbcTimeoutException
import io.r2dbc.spi.Row
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resumeWithException
import kotlin.time.Instant
import kotlin.uuid.Uuid
import io.r2dbc.pool.ConnectionPool as NativeR2dbcConnectionPool
import io.r2dbc.spi.Connection as NativeR2dbcConnection
import io.r2dbc.spi.Result as NativeR2dbcResultSet

/** Runs [block], reporting a driver failure as the matching [SQLError] (a cancellation stays a cancellation). */
internal inline fun <T> rethrowAsSQLError(block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.toSQLError().raise()
    }

internal fun <T> Publisher<T>.toMono(): Mono<T> {
    if (this is Mono<T>) return this
    error("Publisher is not a Mono: ${this::class.qualifiedName}")
}

internal suspend fun NativeR2dbcConnectionPool.acquire(): NativeR2dbcConnection {
    return try {
        // Not awaitSingle(): a connection the pool hands over just as the caller is cancelled would be dropped, and
        // stay checked out for good. It is given back to the pool instead.
        suspendCancellableCoroutine { cont ->
            val subscription = create().subscribe(
                { connection -> cont.resume(connection) { _, c, _ -> c.close().toMono().subscribe() } },
                { error -> cont.resumeWithException(error) },
            )
            cont.invokeOnCancellation { subscription.dispose() }
        }
    } catch (e: CancellationException) {
        throw e
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

/** Runs [block] on a connection borrowed from the pool and returns the connection afterwards. */
internal suspend inline fun <T> NativeR2dbcConnectionPool.withConnection(
    block: NativeR2dbcConnection.() -> T
): Result<T> = runCatching {
    val connection = acquire()
    try {
        rethrowAsSQLError { connection.block() }
    } finally {
        // Released also when the caller is cancelled: in a cancelled coroutine the release would be cancelled
        // before reaching the pool, and the connection would stay checked out for good.
        withContext(NonCancellable) { connection.close().toMono().awaitSingleOrNull() }
    }
}

/**
 * Collects the flow of [block] on a connection borrowed from the pool. The connection is held for the whole
 * collection and returned when the flow completes, fails or is cancelled.
 */
internal fun <T> NativeR2dbcConnectionPool.flowWithConnection(
    block: NativeR2dbcConnection.() -> Flow<T>
): Flow<T> = flow {
    val connection = acquire()
    try {
        emitAll(connection.block())
    } finally {
        withContext(NonCancellable) { connection.close().toMono().awaitSingleOrNull() }
    }
}

/** Runs [block] while holding the lock, once [assertIsOpen] passes. */
internal suspend inline fun <T> Mutex.withLockCatching(
    assertIsOpen: () -> Unit,
    block: () -> T
): Result<T> = runCatching {
    withLock {
        assertIsOpen()
        rethrowAsSQLError(block)
    }
}

/** Collects the flow of [block] while holding the lock (for the whole collection), once [assertIsOpen] passes. */
internal fun <T> Mutex.flowWithLock(
    assertIsOpen: () -> Unit,
    block: () -> Flow<T>
): Flow<T> = flow {
    withLock {
        assertIsOpen()
        emitAll(block())
    }
}

/**
 * Ends a transaction with [block] (a commit or a rollback). The connection is returned to the pool afterwards
 * when [close] is set.
 */
internal suspend inline fun NativeR2dbcConnection.endTransaction(
    close: Boolean,
    block: NativeR2dbcConnection.() -> Unit
) {
    try {
        rethrowAsSQLError { block() }
    } finally {
        if (close) withContext(NonCancellable) { this@endTransaction.close().toMono().awaitSingleOrNull() }
    }
}

/**
 * Rewrites a quote escaped with a backslash in a string (`'O\'Brien'`) to a doubled one (`'O''Brien'`), which MySQL
 * reads the same. r2dbc-mysql finds the `?` placeholders of a statement with a lexer of its own, which knows no
 * backslash escapes: it ended the string at the escaped quote, and missed the placeholders after it.
 */
internal fun String.withQuotesDoubled(): String {
    if ('\\' !in this) return this
    val sb = StringBuilder(length)
    var quote: Char? = null
    var i = 0
    while (i < length) {
        val c = this[i]
        if (quote == null) {
            val commentEnd = when {
                c == '#' || startsWith("--", i) -> indexOf('\n', i).let { if (it < 0) length else it }
                startsWith("/*", i) -> indexOf("*/", i + 2).let { if (it < 0) length else it + 2 }
                else -> -1
            }
            if (commentEnd >= 0) {
                sb.append(this, i, commentEnd)
                i = commentEnd
                continue
            }
            if (c == '\'' || c == '"' || c == '`') quote = c
        } else if (c == '\\' && quote != '`' && i + 1 < length) {
            val next = this[i + 1]
            if (next == quote) sb.append(quote).append(quote) else sb.append(c).append(next)
            i += 2
            continue
        } else if (c == quote) {
            if (i + 1 < length && this[i + 1] == quote) {
                sb.append(c).append(c)
                i += 2
                continue
            }
            quote = null
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

/**
 * INSERT or REPLACE, after any leading comments: the statements whose rows (MariaDB's `RETURNING`) carry the
 * generated id.
 */
private val INSERT_SQL = Regex(
    """^(\s+|--[^\n]*+|#[^\n]*+|/\*.*?\*/)*(insert|replace)\b""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/**
 * Executes the statement and reports its update count and generated id. With returnGeneratedValues(),
 * r2dbc-mysql reports `LAST_INSERT_ID()` from the server's reply to the statement (no extra query); it is 0
 * when the statement generated no id. Every result is consumed: see fetchResultSet.
 */
internal suspend fun io.r2dbc.spi.Statement.executeForResult(sql: String): ExecuteResult {
    var rowsAffected = 0L
    var lastInsertId: Long? = null
    val insert = INSERT_SQL.containsMatchIn(sql)
    returnGeneratedValues().execute().asFlow().collect { result ->
        result
            .flatMap { segment ->
                // An error arrives as a message segment: fail like rowsUpdated would.
                if (segment is NativeR2dbcResultSet.Message) return@flatMap Mono.error(segment.exception())
                // A segment may carry both: the update count and a row (read here, while it is valid).
                val count = (segment as? NativeR2dbcResultSet.UpdateCount)?.value() ?: 0L
                // The generated id is MySQL's synthetic LAST_INSERT_ID row, or the first column of MariaDB's
                // RETURNING row of an insert. Any other row is the statement's own (a SELECT, a procedure's
                // result set): not an id, and not necessarily a number.
                val id = (segment as? NativeR2dbcResultSet.RowSegment)?.row()?.let { row ->
                    val synthetic = row.metadata.columnMetadatas.firstOrNull()?.name == "LAST_INSERT_ID"
                    if (synthetic || insert) (row.get(0) as? Number)?.toLong() else null
                }
                Mono.just(count to id)
            }
            .asFlow()
            .collect { (count, id) ->
                rowsAffected += count
                if (lastInsertId == null) lastInsertId = id
            }
    }
    return ExecuteResult(rowsAffected = rowsAffected, lastInsertId = lastInsertId?.takeIf { it != 0L })
}

/**
 * Executes the statement and reads the rows of its last result. Every result is consumed, in order, as it
 * arrives: a statement can produce several (PostgreSQL's SET produces two), and a result left unconsumed
 * (awaitLast() only kept the last one) left the connection out of step, so the error of the next statement
 * on it was lost and reported as a success.
 */
internal suspend fun io.r2dbc.spi.Statement.fetchResultSet(): ResultSet {
    var last: ResultSet? = null
    execute().asFlow().collect { result -> last = result.toResultSet() }
    return last ?: ResultSet(emptyList(), null, ResultSet.Metadata(emptyList()))
}

internal fun NativeR2dbcConnection.createStatement(
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
    // Only a statement with parameters needs r2dbc-mysql to find its placeholders; one without runs as written,
    // as it then can in any sql_mode (with NO_BACKSLASH_ESCAPES, `'C:\'` is a whole string).
    @Suppress("SqlSourceToSinkFlow")
    val stmt = createStatement(if (query.values.isEmpty()) query.sql else query.sql.withQuotesDoubled())
    query.values.forEachIndexed { index, value ->
        when (value) {
            is TypedNull -> stmt.bindNull(index, value.type.java)
            null -> stmt.bindNull(index, Any::class.java)
            else -> stmt.bind(index, value.toR2dbc())
        }
    }
    return stmt
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
internal fun io.r2dbc.spi.Statement.streamRows(): Flow<ResultSet.Row> = flow {
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
