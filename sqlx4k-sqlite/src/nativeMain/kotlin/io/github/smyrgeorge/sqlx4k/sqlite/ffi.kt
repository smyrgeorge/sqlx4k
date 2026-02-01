@file:OptIn(ExperimentalForeignApi::class)

package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
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
import sqlx4k.sqlite.Sqlx4kSqlitePtr
import sqlx4k.sqlite.Sqlx4kSqliteResult
import sqlx4k.sqlite.Sqlx4kSqliteSchema
import sqlx4k.sqlite.sqlx4k_sqlite_free_result
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private fun Sqlx4kSqliteResult.isError(): Boolean = error >= 0
private fun Sqlx4kSqliteResult.toError(): SQLError {
    val code = SQLError.Code.entries[error]
    val message = error_message?.toKString()
    return SQLError(code, message)
}

fun Sqlx4kSqliteResult.throwIfError() {
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

fun Sqlx4kSqliteResult.toResultSet(): ResultSet {
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

inline fun <T> CPointer<Sqlx4kSqliteResult>?.use(block: (Sqlx4kSqliteResult) -> T): T {
    try {
        return this?.pointed?.let(block)
            ?: throw IllegalStateException("Invalid Sqlx4kSqliteResult pointer: cannot dereference null pointer")
    } finally {
        sqlx4k_sqlite_free_result(this)
    }
}

fun CPointer<Sqlx4kSqliteResult>?.throwIfError(): Unit = use { it.throwIfError() }
fun CPointer<Sqlx4kSqliteResult>?.rtOrError(): CPointer<out CPointed> = use {
    it.throwIfError()
    it.rt ?: SQLError(SQLError.Code.Pool, "Unexpected behaviour while creating the pool.").raise()
}

fun CPointer<Sqlx4kSqliteResult>?.rowsAffectedOrError(): Long = use {
    it.throwIfError()
    it.rows_affected.toLong()
}

suspend inline fun sqlx(
    crossinline operation: (continuationPtr: CPointer<out CPointed>) -> Unit
): CPointer<Sqlx4kSqliteResult>? = suspendCoroutine { continuation ->
    // Create a stable reference to the continuation that won't be garbage collected
    val stableRef = StableRef.create(continuation)

    // Get a C-compatible pointer to the continuation
    val continuationPtr = stableRef.asCPointer()

    // Execute the operation with the continuation pointer
    // The operation is responsible for resuming the continuation
    operation(continuationPtr)
}

val fn = staticCFunction<CValue<Sqlx4kSqlitePtr>, CPointer<Sqlx4kSqliteResult>?, Unit> { c, r ->
    val ref = c.useContents { ptr }!!.asStableRef<Continuation<CPointer<Sqlx4kSqliteResult>?>>()
    ref.get().resume(r)
    ref.dispose()
}
