package io.github.smyrgeorge.sqlx4k

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import librust_lib.Sqlx4kColumn
import librust_lib.Sqlx4kQueryResult
import librust_lib.Sqlx4kResult
import librust_lib.Sqlx4kRow
import librust_lib.sqlx4k_free_query_result
import librust_lib.sqlx4k_free_result

@OptIn(ExperimentalForeignApi::class)
fun <T> CPointer<Sqlx4kResult>?.use(f: (it: Sqlx4kResult) -> T): T {
    val res = this?.pointed?.let { f(it) }
        ?: error("Could not extract the value from the raw pointer (null).")
    // TODO: the above error can leak memory (the free will not be triggered).
    sqlx4k_free_result(this)
    return res
}

@OptIn(ExperimentalForeignApi::class)
fun CPointer<Sqlx4kResult>?.orThrow() {
    val error = use { Sqlx4kError(it.error, it.error_message?.toKString()) }
    error.throwIfError()
}

@OptIn(ExperimentalForeignApi::class)
fun <T> CPointer<Sqlx4kQueryResult>?.use(f: (it: Sqlx4kQueryResult) -> T): T {
    val res = this?.pointed?.let { f(it) }
        ?: error("Could not extract the value from the raw pointer (null).")
    // TODO: the above error can leak memory (the free will not be triggered).
    sqlx4k_free_query_result(this)
    return res
}

@OptIn(ExperimentalForeignApi::class)
fun CPointer<Sqlx4kQueryResult>?.orThrow() {
    val error = use { Sqlx4kError(it.error, it.error_message?.toKString()) }
    error.throwIfError()
}

@OptIn(ExperimentalForeignApi::class)
fun Sqlx4kQueryResult.toStr(): String = buildString {
    append("\n[Sqlx4kPgResult]")
    append("\nerror: $error")
    append("\nerror_message: ${error_message?.toKString()}")
    append("\nsize: $size")
    rows?.let {
        repeat(size) { index ->
            append(it[index].toStr())
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun Sqlx4kRow.toStr(): String {
    return buildString {
        append("\n    [Sqlx4kPgRow]")
        append("\n    size: $size")
        columns?.let {
            repeat(size) { index ->
                append(it[index].toStr())
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun Sqlx4kColumn.toStr(): String {
    return buildString {
        append("\n        [Sqlx4kPgColumn]")
        append("\n        ordinal: $ordinal")
        append("\n        name: ${name?.toKString()}")
        append("\n        kind: $kind")
        append("\n        size: $size")
        append("\n        value: ${value?.readBytes(size)?.toKString()}")
    }
}
