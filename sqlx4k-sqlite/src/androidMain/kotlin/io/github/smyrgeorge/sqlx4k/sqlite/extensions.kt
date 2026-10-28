package io.github.smyrgeorge.sqlx4k.sqlite

import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
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
import io.github.smyrgeorge.sqlx4k.impl.migrate.utils.splitSqlStatements
import io.github.smyrgeorge.sqlx4k.impl.types.SqlRawLiteral
import io.github.smyrgeorge.sqlx4k.impl.types.TypedNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
 * Ends the transaction after [block], which marks it successful for a commit (a rollback leaves it unmarked, so
 * ending it rolls back), and closes the database afterwards when [close] is set. Blocking: run it on the
 * connection's dispatcher, where the transaction was begun.
 *
 * [SQLiteDatabase.endTransaction] is where the COMMIT (or ROLLBACK) actually runs, so its failure is reported (a
 * deferred foreign key violation, a busy or full database, ...); when [block] itself failed, that failure is reported
 * instead. A failure closing the database is ignored.
 */
internal inline fun SQLiteDatabase.completeTransaction(close: Boolean, block: SQLiteDatabase.() -> Unit) {
    try {
        try {
            rethrowAsSQLError { block() }
        } catch (e: Throwable) {
            // Still end the transaction, but keep the original failure.
            runCatching { endTransaction() }
            throw e
        }
        rethrowAsSQLError { endTransaction() }
    } finally {
        if (close) runCatching { close() }
    }
}

/**
 * Commits the outermost transaction, and closes the database afterwards when [close] is set. Blocking: run it on the
 * connection's dispatcher, where the transaction was begun.
 *
 * SQLite keeps the transaction open when its COMMIT fails (a deferred foreign key violation, a busy database, ...),
 * while [SQLiteDatabase.endTransaction] pops it before running the COMMIT: the connection would go on in it, so the
 * next statements on it, of its next borrower too, would run in it and never be committed. A ROLLBACK run then would
 * not reach it either: android.database.sqlite takes ROLLBACK for read-only, and runs it on another connection.
 *
 * The COMMIT is run here instead, while android.database.sqlite still holds the transaction, and so runs every
 * statement on its connection (the leading `;` keeps it from taking the statement for its own COMMIT). When it fails,
 * ending the transaction unmarked rolls it back. When it succeeds, SQLite is out of the transaction, and the COMMIT
 * that ending it runs fails, which is ignored.
 */
internal fun SQLiteDatabase.commitOutermostTransaction(close: Boolean) {
    try {
        try {
            rethrowAsSQLError { execSQL(";COMMIT") }
        } catch (e: Throwable) {
            runCatching { endTransaction() }
            throw e
        }
        runCatching {
            setTransactionSuccessful()
            endTransaction()
        }
    } finally {
        if (close) runCatching { close() }
    }
}

/**
 * Whether running the SQL again could change the database: anything but a query (`SELECT`, `VALUES`, a `WITH` clause
 * whose statements only read, `PRAGMA`, `EXPLAIN`), judged by its first keyword after any leading whitespace and
 * comments, and for a `WITH` clause by the keywords of the statements that write (outside quotes and comments).
 */
internal fun String.mayWrite(): Boolean {
    val code = replace(SQL_QUOTES_AND_COMMENTS, " ").trimStart()
    val keyword = code.takeWhile { it.isLetter() }.lowercase()
    return when (keyword) {
        "select", "values", "pragma", "explain" -> false
        "with" -> SQL_WRITE_KEYWORD.containsMatchIn(code)
        else -> true
    }
}

private val SQL_QUOTES_AND_COMMENTS =
    Regex("""'(?:[^']|'')*'|"(?:[^"]|"")*"|`[^`]*`|\[[^]]*]|--[^\n]*|/\*[\s\S]*?\*/""")

// `replace` followed by `(` is the string function, not a REPLACE statement.
private val SQL_WRITE_KEYWORD = Regex("""\b(insert|update|delete)\b|\breplace\b(?!\s*\()""", RegexOption.IGNORE_CASE)

/**
 * Binds the supplied positional values onto an Android [SQLiteProgram]
 * (`SQLiteStatement` for `compileStatement(sql)` or `SQLiteQuery` from a
 * cursor factory). Mirrors the JVM `setObject` mapping — Char / Instant /
 * `LocalDate*` / `Uuid` are folded down to text, ByteArray to BLOB,
 * primitives to LONG / DOUBLE.
 */
private fun SQLiteProgram.bindAll(values: List<Any?>) {
    values.forEachIndexed { i, value ->
        val index = i + 1
        @OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
        when (value) {
            null -> bindNull(index)
            is TypedNull -> bindNull(index)
            is Boolean -> bindLong(index, if (value) 1L else 0L)
            is Byte -> bindLong(index, value.toLong())
            is Short -> bindLong(index, value.toLong())
            is Int -> bindLong(index, value.toLong())
            is Long -> bindLong(index, value)
            is Float -> bindDouble(index, value.toDouble())
            is Double -> bindDouble(index, value)
            is String -> bindString(index, value)
            is Char -> bindString(index, value.toString())
            is ByteArray -> bindBlob(index, value)
            // SQLite stores dates/times as text; match the canonical
            // `yyyy-MM-dd HH:mm:ss.uuuuuu` shape so round-trips through
            // the existing `asInstant()` / `asLocalDate*()` decoders work.
            is Instant -> bindString(index, value.toTimestampString())
            is LocalDate -> bindString(index, value.toString())
            is LocalTime -> bindString(index, value.toTimeString())
            is LocalDateTime -> bindString(index, value.toTimestampString())
            is Uuid -> bindString(index, value.toString())
            is java.util.UUID -> bindString(index, value.toString())
            is SqlRawLiteral -> bindString(index, value.sql)
            else -> SQLError(
                code = SQLError.Code.MissingValueConverter,
                message = "Cannot bind value of type ${value::class.simpleName} as a SQLite parameter"
            ).raise()
        }
    }
}

/**
 * Renders the statement for SQLite. Rendering runs before the driver call, outside its error mapping, so a
 * rendering failure is reported as it is.
 */
internal fun Statement.render(encoders: ValueEncoderRegistry): Result<Statement.NativeQuery> =
    runCatching { renderNativeQuery(Dialect.SQLite, encoders) }

/**
 * Runs the query with its parameters bound by type. `rawQueryWithFactory` is the only public API that lets us
 * bind typed parameters (long / double / blob / string / null) to a SELECT — `rawQuery` would force every arg
 * through `bindString`. `editTable` is documented as a hint for the cursor (only used by Android's
 * CursorAdapter / undo support); an empty string satisfies the non-null Kotlin signature without consequence.
 */
internal fun SQLiteDatabase.rawQuery(query: Statement.NativeQuery): Cursor = rawQuery(query.sql, query.values)

internal fun SQLiteDatabase.rawQuery(sql: String, values: List<Any?>): Cursor {
    val mayWrite = sql.mayWrite()
    val factory = SQLiteDatabase.CursorFactory { _, driver, editTable, program ->
        program.bindAll(values)
        if (mayWrite) SinglePassCursor(driver, editTable, program) else SQLiteCursor(driver, editTable, program)
    }
    return rawQueryWithFactory(factory, sql, null, "")
}

/**
 * A cursor over the rows of a statement that may write (an `INSERT ... RETURNING`, ...). SQLiteCursor holds a
 * window of the rows (about 2 MB), and reads past it by running the statement again from its start, skipping
 * the rows it read: the statement's writes would be made again (rows inserted twice, ...). Reading past it
 * fails instead.
 */
private class SinglePassCursor(
    driver: android.database.sqlite.SQLiteCursorDriver,
    editTable: String?,
    query: android.database.sqlite.SQLiteQuery,
) : SQLiteCursor(driver, editTable, query) {
    override fun onMove(oldPosition: Int, newPosition: Int): Boolean {
        val window = window
        if (window != null && newPosition !in window.startPosition until window.startPosition + window.numRows) {
            SQLError(
                code = SQLError.Code.Database,
                message = "The rows of a statement that writes do not fit the Android platform driver's " +
                        "cursor window: reading on would run the statement (and its writes) again. " +
                        "Return fewer or smaller rows."
            ).raise()
        }
        return super.onMove(oldPosition, newPosition)
    }
}

/**
 * Runs [sql], which may hold several statements. android.database.sqlite prepares the first statement of the
 * SQL only, and ignores the rest: they are run one by one, as the other targets run them all. The rows
 * affected are those of all of them, the rowid generated that of the last.
 */
internal fun SQLiteDatabase.executeScript(sql: String): ExecuteResult {
    val statements = splitSqlStatements(sql)
    if (statements.size <= 1) return executeForResult(sql)
    var rowsAffected = 0L
    var last: ExecuteResult? = null
    for (statement in statements) {
        last = executeForResult(statement)
        rowsAffected += last.rowsAffected
    }
    return ExecuteResult(rowsAffected = rowsAffected, lastInsertId = last?.lastInsertId)
}

private fun SQLiteDatabase.executeForResult(sql: String): ExecuteResult =
    when (DatabaseUtils.getSqlStatementType(sql)) {
        // Through execSQL, which does more than run them: for ATTACH, it turns write-ahead logging off, so that
        // every connection sees the attached database; after DDL, it drops the idle read connections and the
        // cached statements, which hold the old schema (a query read a table's columns as they were).
        DatabaseUtils.STATEMENT_ATTACH, DatabaseUtils.STATEMENT_DDL -> {
            execSQL(sql)
            ExecuteResult(rowsAffected = 0, lastInsertId = null)
        }

        else -> executeForResult(sql) { compileStatement(sql).use { it.executeUpdateDelete() } }
    }

internal fun SQLiteDatabase.executeForResult(query: Statement.NativeQuery): ExecuteResult =
    executeForResult(query.sql) {
        compileStatement(query.sql).use { stmt ->
            stmt.bindAll(query.values)
            stmt.executeUpdateDelete()
        }
    }

/**
 * Runs [update] (returning the changed row count) and reports the rowid an `INSERT` generated. SQLite keeps the
 * last inserted rowid per connection, and outside a transaction SQLiteDatabase may run the follow-up query on
 * another of its connections: the insert and the read share a transaction, and with it one connection.
 */
private fun SQLiteDatabase.executeForResult(sql: String, update: () -> Int): ExecuteResult {
    if (!sql.isInsertStatement()) return ExecuteResult(rowsAffected = update().toLong(), lastInsertId = null)
    val ownTransaction = !inTransaction()
    if (ownTransaction) beginTransactionNonExclusive()
    try {
        fun lastInsertRowid(): Long = compileStatement("select last_insert_rowid()").use { it.simpleQueryForLong() }
        // An UPSERT that updates the row instead of inserting it leaves the last inserted rowid as it was (an
        // earlier insert's), with the row counted as changed: it is the UPSERT's only when it changed.
        val before = if (sql.isUpsertStatement()) lastInsertRowid() else null
        val rowsAffected = update().toLong()
        val lastInsertId = if (rowsAffected > 0) lastInsertRowid().takeIf { it != before } else null
        if (ownTransaction) setTransactionSuccessful()
        return ExecuteResult(rowsAffected = rowsAffected, lastInsertId = lastInsertId)
    } finally {
        if (ownTransaction) endTransaction()
    }
}

/** The row the cursor is positioned on. */
private fun Cursor.toRow(): ResultSet.Row {
    val columns = (0 until columnCount).map { i ->
        val type = getType(i).toTypeName()
        if (getType(i) == Cursor.FIELD_TYPE_BLOB) {
            return@map ResultSet.Row.Column(
                ordinal = i,
                name = getColumnName(i),
                type = type,
                value = null,
                bytes = if (isNull(i)) null else getBlob(i),
            )
        }
        val value = when (getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> if (isNull(i)) null else getLong(i).toString()
            Cursor.FIELD_TYPE_FLOAT -> if (isNull(i)) null else formatSqliteReal(getDouble(i))
            Cursor.FIELD_TYPE_STRING -> if (isNull(i)) null else getString(i)
            else -> if (isNull(i)) null else getString(i)
        }
        ResultSet.Row.Column(
            ordinal = i,
            name = getColumnName(i),
            type = type,
            value = value,
        )
    }
    return ResultSet.Row(columns)
}

/**
 * Streams the rows of a query: the cursor is opened and stepped row by row on the connection's
 * [dispatcher] (the platform driver is blocking and its transaction state is thread-bound), handing
 * the rows to the collector through a buffer of [fetchSize] rows; the cursor is closed when the flow
 * completes, fails or is cancelled. A driver failure fails the collection with the matching [SQLError].
 *
 * @param query runs the query and returns its cursor.
 */
internal fun cursorRows(
    fetchSize: Int,
    dispatcher: CoroutineDispatcher,
    query: () -> Cursor
): Flow<ResultSet.Row> = flow {
    query().use { cursor ->
        while (cursor.moveToNext()) emit(cursor.toRow())
    }
}.buffer(fetchSize.coerceAtLeast(1)).flowOn(dispatcher).catch { e -> throw (e as? SQLError) ?: e.toSQLError() }

internal fun Cursor.toResultSet(): ResultSet {
    val rows = mutableListOf<ResultSet.Row>()
    if (moveToFirst()) {
        do {
            rows.add(toRow())
        } while (moveToNext())
    }
    // Derive metadata from the first row when available; on an empty result the cursor still
    // exposes column names/ordinals (SQLite types are per-value, so type is reported as "UNKNOWN").
    val meta = if (rows.isNotEmpty()) {
        rows.first().toMetadata()
    } else {
        ResultSet.Metadata(
            (0 until columnCount).map { i ->
                ResultSet.Metadata.Column(ordinal = i, name = getColumnName(i), type = "UNKNOWN")
            }
        )
    }
    return ResultSet(rows, null, meta)
}

private fun Int.toTypeName(): String = when (this) {
    Cursor.FIELD_TYPE_NULL -> "NULL"
    Cursor.FIELD_TYPE_INTEGER -> "INTEGER"
    Cursor.FIELD_TYPE_FLOAT -> "REAL"
    Cursor.FIELD_TYPE_STRING -> "TEXT"
    Cursor.FIELD_TYPE_BLOB -> "BLOB"
    else -> "UNKNOWN"
}
