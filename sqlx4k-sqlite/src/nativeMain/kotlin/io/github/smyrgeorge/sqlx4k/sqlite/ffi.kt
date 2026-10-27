@file:OptIn(ExperimentalForeignApi::class)

package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ExecuteResult
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.extensions.isInsertStatement
import io.github.smyrgeorge.sqlx4k.impl.extensions.isUpsertStatement
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import sqlx4k.sqlite.Sqlx4kSqlitePtr
import sqlx4k.sqlite.Sqlx4kSqliteResult
import sqlx4k.sqlite.Sqlx4kSqliteSchema
import sqlx4k.sqlite.sqlx4k_sqlite_free_result
import sqlx4k.sqlite.sqlx4k_sqlite_stream_close
import sqlx4k.sqlite.sqlx4k_sqlite_stream_next
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

private fun Sqlx4kSqliteResult.isError(): Boolean = error >= 0
private fun Sqlx4kSqliteResult.toError(): SQLError {
    val code = SQLError.Code.entries[error]
    val message = error_message?.toKString()
    // Database errors also carry what the database reported (see `SQLError`); the Rust side uses
    // null / -1 when a value is not available for this driver.
    val sqlState = error_sql_state?.toKString()
    val nativeCode = error_native_code.takeIf { it >= 0 }
    val kind = SQLError.Kind.entries.getOrElse(error_kind) { SQLError.Kind.Other }
    return SQLError(code = code, message = message, sqlState = sqlState, nativeCode = nativeCode, kind = kind)
}

internal fun Sqlx4kSqliteResult.throwIfError() {
    if (isError()) toError().raise()
}

private fun Sqlx4kSqliteSchema.toMetadata(): ResultSet.Metadata {
    val columns = List(size) { colIndex ->
        val column = requireNotNull(columns) { "Schema columns cannot be null" }[colIndex]

        ResultSet.Metadata.Column(
            ordinal = column.ordinal,
            name = requireNotNull(column.name) { "Column name at index $colIndex cannot be null" }.toKString(),
            type = requireNotNull(column.kind) { "Column type at index $colIndex cannot be null" }.toKString()
        )
    }

    return ResultSet.Metadata(columns)
}

private fun Sqlx4kSqliteResult.toResultSet(): ResultSet {
    // Process rows
    val rows = List(size) { rowIndex ->
        val row = requireNotNull(rows) { "Rows cannot be null" }[rowIndex]

        // Process columns for this row
        val columns = List(row.size) { colIndex ->
            val column = requireNotNull(row.columns) { "Row columns cannot be null" }[colIndex]
            val schemaColumn = requireNotNull(schema!!.pointed.columns) { "Schema columns cannot be null" }[colIndex]

            ResultSet.Row.Column(
                ordinal = column.ordinal,
                name = requireNotNull(schemaColumn.name) { "Column name cannot be null" }.toKString(),
                type = requireNotNull(schemaColumn.kind) { "Column type cannot be null" }.toKString(),
                // TEXT may contain NUL bytes, so read the full length rather than up to the first NUL.
                value = column.value?.readBytes(column.value_len)?.decodeToString()
            )
        }

        ResultSet.Row(columns)
    }

    // Process error information if present
    val error: SQLError? = if (isError()) toError() else null

    // Get schema information
    val metadata = if (schema != null) schema!!.pointed.toMetadata() else ResultSet.Metadata(emptyList())

    return ResultSet(rows, error, metadata)
}

internal inline fun <T> CPointer<Sqlx4kSqliteResult>?.use(block: (Sqlx4kSqliteResult) -> T): T {
    try {
        return this?.pointed?.let(block)
            ?: throw IllegalStateException("Invalid Sqlx4kSqliteResult pointer: cannot dereference null pointer")
    } finally {
        sqlx4k_sqlite_free_result(this)
    }
}

internal fun CPointer<Sqlx4kSqliteResult>?.throwIfError(): Unit = use { it.throwIfError() }
internal fun CPointer<Sqlx4kSqliteResult>?.rtOrError(): CPointer<out CPointed> = use {
    it.throwIfError()
    it.rt ?: SQLError(SQLError.Code.Pool, "Unexpected behaviour while creating the pool.").raise()
}

internal fun CPointer<Sqlx4kSqliteResult>?.executeResultOrError(sql: String): ExecuteResult =
    use { it.toExecuteResultOrError(sql) }

internal fun CPointer<Sqlx4kSqliteResult>?.resultSetOrError(): ResultSet = use { it.toResultSetOrError() }

/**
 * SQLite keeps the last inserted rowid per connection, so it is the statement's own generated id only for an
 * `INSERT` that inserted rows; otherwise it would be a previous insert's. The native side reports it as 0 (none) for
 * an UPSERT that left it unchanged, having updated the row instead of inserting it.
 */
private fun Sqlx4kSqliteResult.toExecuteResult(sql: String): ExecuteResult {
    val rowsAffected = rows_affected.toLong()
    val lastInsertId = last_insert_id.takeIf {
        rowsAffected > 0 && sql.isInsertStatement() && (it != 0L || !sql.isUpsertStatement())
    }
    return ExecuteResult(rowsAffected = rowsAffected, lastInsertId = lastInsertId)
}

internal fun Sqlx4kSqliteResult.toExecuteResultOrError(sql: String): ExecuteResult {
    throwIfError()
    return toExecuteResult(sql)
}

internal fun Sqlx4kSqliteResult.toResultSetOrError(): ResultSet {
    throwIfError()
    return toResultSet()
}

/**
 * Runs an FFI [operation] and suspends until the Rust side reports its result through [fn].
 *
 * The call cannot be cancelled: Rust always completes it, and its result may own a resource that must be reclaimed
 * (a connection or transaction handle, a row stream, a transaction moved into the call). So the result is always
 * awaited, also when the caller is cancelled: [NonCancellable] keeps the continuation from being cancelled, and since
 * it does not change the dispatcher, `withContext` adds no cancellation check on entry or exit. The caller observes a
 * cancellation at its next suspension point.
 */
internal suspend inline fun sqlx(
    crossinline operation: (continuationPtr: CPointer<out CPointed>) -> Unit
): CPointer<Sqlx4kSqliteResult>? = withContext(NonCancellable) {
    suspendCancellableCoroutine { continuation ->
        // Create a stable reference to the continuation that won't be garbage collected
        val stableRef = StableRef.create(continuation)

        // Get a C-compatible pointer to the continuation
        val continuationPtr = stableRef.asCPointer()

        // Execute the operation with the continuation pointer
        // The operation is responsible for resuming the continuation
        operation(continuationPtr)
    }
}

internal val fn = staticCFunction<CValue<Sqlx4kSqlitePtr>, CPointer<Sqlx4kSqliteResult>?, Unit> { c, r ->
    val ref = c.useContents { ptr }!!.asStableRef<Continuation<CPointer<Sqlx4kSqliteResult>?>>()
    ref.get().resume(r)
    ref.dispose()
}

/**
 * Collects a row stream as a cold flow. [open] calls one of the `sqlx4k_sqlite_*_stream_open` functions and returns
 * its result, which carries the stream handle. Rows then arrive in chunks of the requested fetch size, each chunk
 * crossing the FFI boundary as a regular result that is freed right after its rows are emitted; an empty chunk marks
 * the end of the stream and an error chunk fails the collection with the [SQLError].
 *
 * The stream is always closed, also when the collector is cancelled or fails. Closing waits for the Rust side to stop
 * using the connection, so the caller may release the connection (or commit the transaction) right after.
 */
internal fun streamFlow(
    rt: CPointer<out CPointed>,
    open: suspend () -> CPointer<Sqlx4kSqliteResult>?,
): Flow<ResultSet.Row> = flow {
    val stream: CPointer<out CPointed> = open().use {
        it.throwIfError()
        it.stream ?: SQLError(SQLError.Code.Database, "Unexpected behaviour while opening the row stream.").raise()
    }
    try {
        while (true) {
            val chunk = sqlx { c -> sqlx4k_sqlite_stream_next(rt, stream, c, fn) }.use { it.toResultSet() }
            chunk.throwIfError()
            if (chunk.size == 0) break
            chunk.rows.forEach { emit(it) }
        }
    } finally {
        withContext(NonCancellable) {
            sqlx { c -> sqlx4k_sqlite_stream_close(rt, stream, c, fn) }.use { it.throwIfError() }
        }
    }
}

/**
 * Returns [resource] (a connection or a transaction a [sqlx] call acquired), unless the caller was cancelled during the
 * call: the call completes also then, and the caller (e.g. one timed out by `withTimeoutOrNull`) never receives it, so
 * it would never go back to the pool. It is [release]d then, and the cancellation thrown.
 */
internal suspend inline fun <T> releasedIfCancelled(resource: T, crossinline release: suspend (T) -> Unit): T {
    val context = currentCoroutineContext()
    if (!context.isActive) {
        withContext(NonCancellable) { release(resource) }
        context.ensureActive()
    }
    return resource
}
