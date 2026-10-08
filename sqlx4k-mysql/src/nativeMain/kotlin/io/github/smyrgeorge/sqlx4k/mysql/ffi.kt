@file:OptIn(ExperimentalForeignApi::class)

package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import sqlx4k.mysql.Sqlx4kMysqlPtr
import sqlx4k.mysql.Sqlx4kMysqlResult
import sqlx4k.mysql.Sqlx4kMysqlSchema
import sqlx4k.mysql.sqlx4k_mysql_free_result
import sqlx4k.mysql.sqlx4k_mysql_stream_close
import sqlx4k.mysql.sqlx4k_mysql_stream_next
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private fun Sqlx4kMysqlResult.isError(): Boolean = error >= 0
private fun Sqlx4kMysqlResult.toError(): SQLError {
    val code = SQLError.Code.entries[error]
    val message = error_message?.toKString()
    // Database errors also carry what the database reported (see `SQLError`); the Rust side uses
    // null / -1 when a value is not available for this driver.
    val sqlState = error_sql_state?.toKString()
    val nativeCode = error_native_code.takeIf { it >= 0 }
    val kind = SQLError.Kind.entries.getOrElse(error_kind) { SQLError.Kind.Other }
    return SQLError(code = code, message = message, sqlState = sqlState, nativeCode = nativeCode, kind = kind)
}

fun Sqlx4kMysqlResult.throwIfError() {
    if (isError()) toError().raise()
}

private fun Sqlx4kMysqlSchema.toMetadata(): ResultSet.Metadata {
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

fun Sqlx4kMysqlResult.toResultSet(): ResultSet {
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
                value = column.value?.toKString()
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

inline fun <T> CPointer<Sqlx4kMysqlResult>?.use(block: (Sqlx4kMysqlResult) -> T): T {
    try {
        return this?.pointed?.let(block)
            ?: throw IllegalStateException("Invalid Sqlx4kMysqlResult pointer: cannot dereference null pointer")
    } finally {
        sqlx4k_mysql_free_result(this)
    }
}

fun CPointer<Sqlx4kMysqlResult>?.throwIfError(): Unit = use { it.throwIfError() }
fun CPointer<Sqlx4kMysqlResult>?.rtOrError(): CPointer<out CPointed> = use {
    it.throwIfError()
    it.rt ?: SQLError(SQLError.Code.Pool, "Unexpected behaviour while creating the pool.").raise()
}

fun CPointer<Sqlx4kMysqlResult>?.rowsAffectedOrError(): Long = use {
    it.throwIfError()
    it.rows_affected.toLong()
}

suspend inline fun sqlx(
    crossinline operation: (continuationPtr: CPointer<out CPointed>) -> Unit
): CPointer<Sqlx4kMysqlResult>? = suspendCoroutine { continuation ->
    // Create a stable reference to the continuation that won't be garbage collected
    val stableRef = StableRef.create(continuation)

    // Get a C-compatible pointer to the continuation
    val continuationPtr = stableRef.asCPointer()

    // Execute the operation with the continuation pointer
    // The operation is responsible for resuming the continuation
    operation(continuationPtr)
}

val fn = staticCFunction<CValue<Sqlx4kMysqlPtr>, CPointer<Sqlx4kMysqlResult>?, Unit> { c, r ->
    val ref = c.useContents { ptr }!!.asStableRef<Continuation<CPointer<Sqlx4kMysqlResult>?>>()
    ref.get().resume(r)
    ref.dispose()
}

/**
 * Collects a row stream as a cold flow. [open] calls one of the `sqlx4k_mysql_*_stream_open` functions and returns
 * its result, which carries the stream handle. Rows then arrive in chunks of the requested fetch size, each chunk
 * crossing the FFI boundary as a regular result that is freed right after its rows are emitted; an empty chunk marks
 * the end of the stream and an error chunk fails the collection with the [SQLError].
 *
 * The stream is always closed, also when the collector is cancelled or fails. Closing waits for the Rust side to stop
 * using the connection, so the caller may release the connection (or commit the transaction) right after.
 */
internal fun streamFlow(
    rt: CPointer<out CPointed>,
    open: suspend () -> CPointer<Sqlx4kMysqlResult>?,
): Flow<ResultSet.Row> = flow {
    val stream: CPointer<out CPointed> = open().use {
        it.throwIfError()
        it.stream ?: SQLError(SQLError.Code.Database, "Unexpected behaviour while opening the row stream.").raise()
    }
    try {
        while (true) {
            val chunk = sqlx { c -> sqlx4k_mysql_stream_next(rt, stream, c, fn) }.use { it.toResultSet() }
            chunk.throwIfError()
            if (chunk.size == 0) break
            chunk.rows.forEach { emit(it) }
        }
    } finally {
        withContext(NonCancellable) {
            sqlx { c -> sqlx4k_mysql_stream_close(rt, stream, c, fn) }.use { it.throwIfError() }
        }
    }
}
