package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.Dialect
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
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toJavaLocalTime
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import reactor.pool.PoolAcquireTimeoutException
import reactor.pool.PoolShutdownException
import kotlin.coroutines.resumeWithException
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
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

/**
 * Waits for the connection this opens (or borrows from a pool). Not awaitSingle(): a connection handed over just as the
 * caller is cancelled would be dropped, and stay open (or checked out) for good. It is closed (given back) instead.
 */
internal suspend fun <C : NativeR2dbcConnection> Mono<C>.awaitConnection(): C =
    suspendCancellableCoroutine { cont ->
        val subscription = subscribe(
            { connection -> cont.resume(connection) { _, c, _ -> c.close().toMono().subscribe() } },
            { error -> cont.resumeWithException(error) },
        )
        cont.invokeOnCancellation { subscription.dispose() }
    }

internal suspend fun NativeR2dbcConnectionPool.acquire(): NativeR2dbcConnection {
    return try {
        create().awaitConnection()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        when (e) {
            is PoolShutdownException -> SQLError(SQLError.Code.PoolClosed, e.message, e).raise()
            is PoolAcquireTimeoutException -> SQLError(SQLError.Code.PoolTimedOut, e.message, e).raise()
            // r2dbc-pool enforces the acquire timeout itself and reports it as an R2dbcTimeoutException.
            is R2dbcTimeoutException -> SQLError(SQLError.Code.PoolTimedOut, e.message, e).raise()
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

internal fun NativeR2dbcConnection.createStatement(
    statement: Statement,
    encoders: ValueEncoderRegistry
): io.r2dbc.spi.Statement {
    fun Any.toR2dbc(): Any = when (this) {
        is Instant -> toJavaInstant()
        is LocalDate -> toJavaLocalDate()
        is LocalTime -> toJavaLocalTime()
        is LocalDateTime -> toJavaLocalDateTime()
        is Uuid -> toJavaUuid()
        else -> this
    }

    // Kotlin stdlib / kotlinx-datetime types must be mapped to their java.time equivalents,
    // so r2dbc (which only understands java.time.*) can resolve the SQL type for bindNull.
    fun KClass<*>.toR2dbcType(): Class<*> = when (this) {
        Instant::class -> Class.forName("java.time.Instant")
        LocalDate::class -> Class.forName("java.time.LocalDate")
        LocalTime::class -> Class.forName("java.time.LocalTime")
        LocalDateTime::class -> Class.forName("java.time.LocalDateTime")
        Uuid::class -> Class.forName("java.util.UUID")
        else -> this.java
    }

    val query = statement.renderNativeQuery(Dialect.PostgreSQL, encoders)

    @Suppress("SqlSourceToSinkFlow")
    val stmt = createStatement(query.sql)
    query.values.forEachIndexed { index, value ->
        when (value) {
            is TypedNull -> stmt.bindNull(index, value.type.toR2dbcType())
            null -> stmt.bindNull(index, Any::class.java)
            else -> stmt.bind(index, value.toR2dbc())
        }
    }
    return stmt
}

/**
 * Executes the statement and reports its update count (0 when the statement reports none), summed over its
 * results. Every result is consumed: see fetchResultSet.
 */
internal suspend fun io.r2dbc.spi.Statement.executeForRowsUpdated(): Long {
    var rows = 0L
    execute().asFlow().collect { result -> rows += result.rowsUpdated.toMono().awaitSingleOrNull() ?: 0 }
    return rows
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

private fun Row.toRow(): ResultSet.Row {
    val columns = metadata.columnMetadatas.mapIndexed { i, c ->
        val type = c.type.name
        ResultSet.Row.Column(
            ordinal = i,
            name = c.name,
            type = type,
            value = when (type) {
                in TEXT_ARRAY_TYPES -> get(i, Array<String?>::class.java)?.toPgArrayText()
                // In the hex form (`\x6100`): with `bytea_output = 'escape'` the server writes `a\000`, which
                // the decoders (as the native driver's output) do not read, and r2dbc does not decode.
                "BYTEA" -> get(i, String::class.java)?.let { if (it.startsWith("\\x")) it else it.byteaEscapeToHex() }
                else -> get(i, String::class.java)
            }
        )
    }
    return ResultSet.Row(columns)
}

/** Converts bytea's escape output form (`\\` is a backslash, `\ooo` an octal byte) to the hex form. */
private fun String.byteaEscapeToHex(): String = buildString(length * 2 + 2) {
    append("\\x")
    val text = this@byteaEscapeToHex
    var i = 0
    while (i < text.length) {
        val byte = when {
            text[i] != '\\' -> text[i++].code
            text.getOrNull(i + 1) == '\\' -> '\\'.code.also { i += 2 }
            else -> text.substring(i + 1, i + 4).toInt(8).also { i += 4 }
        }
        append(byte.toByte().toHexString())
    }
}

/**
 * Text-like array types. r2dbc-postgresql cannot decode them to a String ("Dimensions mismatch"), so they are
 * read as arrays and rendered like the native driver does (see [toPgArrayText]).
 */
private val TEXT_ARRAY_TYPES = setOf("TEXT_ARRAY", "VARCHAR_ARRAY", "BPCHAR_ARRAY", "NAME_ARRAY")

/**
 * Renders the array as PostgreSQL writes it (`{a,"b,c",NULL}`), as the native driver does: an element that is
 * empty, reads `NULL`, or holds a delimiter, quote, backslash or whitespace is quoted, so that the `as*Array`
 * decoders read it back as it is.
 */
private fun Array<String?>.toPgArrayText(): String = joinToString(",", "{", "}") { it?.toPgArrayElement() ?: "NULL" }

private fun String.toPgArrayElement(): String {
    val quote = isEmpty() || equals("NULL", ignoreCase = true) ||
            any { it == '{' || it == '}' || it == ',' || it == '"' || it == '\\' || it.isWhitespace() }
    if (!quote) return this
    return buildString(length + 2) {
        append('"')
        this@toPgArrayElement.forEach { if (it == '"' || it == '\\') append('\\'); append(it) }
        append('"')
    }
}

/**
 * Streams the rows of the statement with reactive backpressure (R2DBC publishes the rows as the
 * server sends them); a driver failure fails the collection with the matching [SQLError].
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
