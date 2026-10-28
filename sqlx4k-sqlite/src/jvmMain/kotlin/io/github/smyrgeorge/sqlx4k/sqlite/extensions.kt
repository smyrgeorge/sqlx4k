package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.ExecuteResult
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.extensions.isInsertStatement
import io.github.smyrgeorge.sqlx4k.impl.extensions.isUpsertStatement
import io.github.smyrgeorge.sqlx4k.impl.extensions.toTimeString
import io.github.smyrgeorge.sqlx4k.impl.extensions.toTimestampString
import io.github.smyrgeorge.sqlx4k.impl.types.TypedNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toJavaLocalDate
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import java.sql.Connection as NativeJdbcConnection
import java.sql.ResultSet as NativeJdbcResultSet

/** Runs [block], reporting a driver failure as the matching [SQLError] (a cancellation stays a cancellation). */
internal inline fun <T> rethrowAsSQLError(block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.toSQLError().raise()
    }

/** Runs [block] on a connection acquired from the pool and closes (releases) the connection afterwards. */
internal suspend inline fun <T> ConnectionPool.withConnection(block: Connection.() -> T): Result<T> = runCatching {
    val connection = acquire().getOrThrow()
    try {
        rethrowAsSQLError { connection.block() }
    } finally {
        // Released also when the caller is cancelled, so the connection always goes back to the pool.
        withContext(NonCancellable) { connection.close() }
    }
}

/**
 * Collects the flow of [block] on a connection acquired from the pool. The connection is held for the whole
 * collection and released when the flow completes, fails or is cancelled.
 */
internal fun <T> ConnectionPool.flowWithConnection(block: Connection.() -> Flow<T>): Flow<T> = flow {
    val connection = acquire().getOrThrow()
    try {
        emitAll(connection.block())
    } finally {
        withContext(NonCancellable) { connection.close() }
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
 * Ends a transaction with [block] (a commit or a rollback). The connection is closed (returned to the pool)
 * afterwards when [close] is set. Blocking: call it on [kotlinx.coroutines.Dispatchers.IO].
 */
internal inline fun NativeJdbcConnection.endTransaction(close: Boolean, block: NativeJdbcConnection.() -> Unit) {
    try {
        rethrowAsSQLError { block() }
    } finally {
        if (close) close()
    }
}

internal fun NativeJdbcConnection.prepareStatement(
    statement: Statement,
    encoders: ValueEncoderRegistry
): java.sql.PreparedStatement {
    @OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
    fun Any?.toJdbc(): Any? = when (this) {
        null -> null
        is Char -> toString()
        // SQLite stores dates/times as text in `yyyy-MM-dd HH:mm:ss.uuuuuu` format,
        // matching how the common `encodeValue` renders Instant values so that
        // round-trip parsing through `asInstant` is consistent.
        is Instant -> toTimestampString()
        is LocalDate -> toJavaLocalDate()
        is LocalTime -> toTimeString()
        is LocalDateTime -> toTimestampString()
        // SQLite has no native UUID type; store as string.
        // Also handle java.util.UUID in case Kotlin's Uuid value class is stored as its underlying type.
        is Uuid -> toString()
        is java.util.UUID -> toString()
        else -> this
    }

    val query = statement.renderNativeQuery(Dialect.SQLite, encoders)

    @Suppress("SqlSourceToSinkFlow")
    val stmt = prepareStatement(query.sql)
    query.values.forEachIndexed { index, value ->
        when (value) {
            is TypedNull -> stmt.setNull(index + 1, java.sql.Types.NULL)
            null -> stmt.setNull(index + 1, java.sql.Types.NULL)
            else -> stmt.setObject(index + 1, value.toJdbc())
        }
    }
    return stmt
}

/** The current row of the result set. */
private fun NativeJdbcResultSet.toRow(): ResultSet.Row {
    val metaData = this.metaData
    val columns = (1..metaData.columnCount).map { i ->
        val type = metaData.getColumnTypeName(i)
        // getObject reflects the value's storage class, not the column's declared type (a BINARY(16) column
        // holds blobs, a BLOB column may hold text): a blob read as text would be corrupted.
        val raw = getObject(i)
        ResultSet.Row.Column(
            ordinal = i - 1,
            name = metaData.getColumnName(i),
            type = type,
            value = when (raw) {
                null, is ByteArray -> null
                // Render REAL values ourselves (see formatSqliteReal).
                is Double -> formatSqliteReal(raw)
                else -> getString(i)
            },
            bytes = raw as? ByteArray
        )
    }
    return ResultSet.Row(columns)
}

/**
 * Streams the rows of a JDBC query. The driver is blocking, so the statement is executed and the result
 * set stepped row by row on [Dispatchers.IO], handing the rows to the collector through a buffer of
 * [fetchSize] rows; the statement (and with it the result set) is closed when the flow completes, fails
 * or is cancelled. A driver failure fails the collection with the matching [SQLError].
 *
 * @param execute executes the query and returns the statement to close along with its result set.
 */
internal fun jdbcRows(
    fetchSize: Int,
    execute: () -> Pair<java.sql.Statement, NativeJdbcResultSet>
): Flow<ResultSet.Row> = flow {
    val (stmt, rs) = execute()
    stmt.use {
        while (rs.next()) emit(rs.toRow())
    }
}.buffer(fetchSize.coerceAtLeast(1)).flowOn(Dispatchers.IO)
    .catch { e -> throw (e as? SQLError) ?: e.toSQLError() }

internal fun NativeJdbcResultSet.toResultSet(): ResultSet {
    val rows = mutableListOf<ResultSet.Row>()
    while (next()) {
        rows.add(toRow())
    }
    // Build column metadata from the JDBC ResultSetMetaData rather than the first row, so an
    // empty result set still reports its columns (names/types) instead of losing them.
    val metaData = this.metaData
    val meta = ResultSet.Metadata(
        (1..metaData.columnCount).map { i ->
            ResultSet.Metadata.Column(
                ordinal = i - 1,
                name = metaData.getColumnName(i),
                type = metaData.getColumnTypeName(i)
            )
        }
    )
    return ResultSet(rows, null, meta)
}

/**
 * Runs [update] and reports the rowid it generated, read on the same connection right after. SQLite keeps the
 * last inserted rowid per connection, so it is the statement's own only for an `INSERT` that inserted rows.
 */
internal fun NativeJdbcConnection.executeForResult(sql: String, update: () -> Int): ExecuteResult {
    fun lastInsertRowid(): Long = createStatement().use { stmt ->
        @Suppress("SqlNoDataSourceInspection")
        stmt.executeQuery("select last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) }
    }
    // An UPSERT that updates the row instead of inserting it leaves the last inserted rowid as it was (an
    // earlier insert's), with the row counted as changed: it is the UPSERT's only when it changed.
    val before = if (sql.isUpsertStatement()) lastInsertRowid() else null
    val rowsAffected = update().toLong()
    val lastInsertId = if (rowsAffected > 0 && sql.isInsertStatement()) {
        lastInsertRowid().takeIf { it != before }
    } else {
        null
    }
    return ExecuteResult(rowsAffected = rowsAffected, lastInsertId = lastInsertId)
}
