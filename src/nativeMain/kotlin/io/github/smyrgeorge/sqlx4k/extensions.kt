package io.github.smyrgeorge.sqlx4k

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import librust_lib.Sqlx4kColumn
import librust_lib.Sqlx4kResult
import librust_lib.Sqlx4kRow
import librust_lib.sqlx4k_free

@OptIn(ExperimentalForeignApi::class)
fun Sqlx4kResult.toStr(): String = buildString {
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

@OptIn(ExperimentalForeignApi::class)
fun CPointer<Sqlx4kResult>?.use(f: (it: Sqlx4kResult) -> Unit) {
    val res = this?.pointed ?: error("Could not extract the value from the raw pointer (null).")
    f(res)
    sqlx4k_free(this)
}
