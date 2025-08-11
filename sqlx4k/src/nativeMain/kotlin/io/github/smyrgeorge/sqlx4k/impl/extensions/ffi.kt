@file:OptIn(ExperimentalForeignApi::class)

package io.github.smyrgeorge.sqlx4k.impl.extensions

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import sqlx4k.Sqlx4kResult
import sqlx4k.Sqlx4kSchema
import sqlx4k.sqlx4k_free_result
import kotlin.coroutines.suspendCoroutine

private fun Sqlx4kResult.isError(): Boolean = error >= 0
private fun Sqlx4kResult.toError(): SQLError {
    val code = SQLError.Code.entries[error]
    val message = error_message?.toKString()
    return SQLError(code, message)
}

fun Sqlx4kResult.throwIfError() {
    if (isError()) toError().ex()
}

/**
 * Converts a Sqlx4kSchema to ResultSet.Metadata.
 *
 * Creates metadata information for a result set by extracting column definitions
 * from the schema.
 *
 * @return ResultSet.Metadata containing column information
 */
private fun Sqlx4kSchema.toMetadata(): ResultSet.Metadata {
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

/**
 * Converts a Sqlx4kResult to ResultSet.
 *
 * @return A ResultSet containing structured data from the Sqlx4kResult
 */
fun Sqlx4kResult.toResultSet(): ResultSet {
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

/**
 * Safely executes an operation with a Sqlx4kResult pointer and ensures proper resource cleanup.
 *
 * This inline function provides a scope for working with potentially nullable Sqlx4kResult
 * pointers while guaranteeing that the associated resources are properly released after use,
 * regardless of whether the operation succeeds or fails.
 *
 * @param T The return type of the operation
 * @param block The operation to perform with the dereferenced Sqlx4kResult
 * @return The result of the operation
 * @throws IllegalStateException If the pointer is null or cannot be dereferenced
 */
inline fun <T> CPointer<Sqlx4kResult>?.use(block: (Sqlx4kResult) -> T): T {
    try {
        return this?.pointed?.let(block)
            ?: throw IllegalStateException("Invalid Sqlx4kResult pointer: cannot dereference null pointer")
    } finally {
        sqlx4k_free_result(this)
    }
}

fun CPointer<Sqlx4kResult>?.throwIfError(): Unit = use { it.throwIfError() }
fun CPointer<Sqlx4kResult>?.rtOrError(): CPointer<out CPointed> = use {
    it.throwIfError()
    it.rt ?: SQLError(SQLError.Code.Pool, "Unexpected behaviour while creating the pool.").ex()
}

fun CPointer<Sqlx4kResult>?.rowsAffectedOrError(): Long = use {
    it.throwIfError()
    it.rows_affected.toLong()
}

/**
 * Executes an SQLx operation asynchronously using Kotlin coroutines.
 *
 * This function transforms a callback-based SQLx operation into a suspending function,
 * allowing for more readable asynchronous code. It creates a stable reference to the
 * continuation and passes its pointer to the callback function, which is expected to
 * resume the continuation when the operation completes.
 *
 * @param operation A function that takes a pointer to the continuation and initiates
 *                  the SQLx operation. This function should eventually resume the continuation
 *                  with the result pointer.
 * @return The SQLx result pointer, or null if the operation did not produce a result
 */
suspend inline fun sqlx(
    crossinline operation: (continuationPtr: CPointer<out CPointed>) -> Unit
): CPointer<Sqlx4kResult>? = suspendCoroutine { continuation ->
    // Create a stable reference to the continuation that won't be garbage collected
    val stableRef = StableRef.create(continuation)

    // Get a C-compatible pointer to the continuation
    val continuationPtr = stableRef.asCPointer()

    // Execute the operation with the continuation pointer
    // The operation is responsible for resuming the continuation
    operation(continuationPtr)
}
