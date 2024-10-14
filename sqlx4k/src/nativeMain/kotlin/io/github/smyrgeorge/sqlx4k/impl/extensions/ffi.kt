@file:OptIn(ExperimentalForeignApi::class)

package io.github.smyrgeorge.sqlx4k.impl.extensions

import io.github.smyrgeorge.sqlx4k.SQLError
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import sqlx4k.Sqlx4kColumn
import sqlx4k.Sqlx4kResult
import sqlx4k.Sqlx4kRow
import sqlx4k.sqlx4k_free_result
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

fun Sqlx4kRow.debug(prefix: String = ""): String = buildString {
    append("\n$prefix[Sqlx4kPgRow]")
    append("\n${prefix}size: $size")
    columns?.let {
        repeat(size) { index -> append(it[index].debug(prefix = "$prefix    ")) }
    }
}

fun Sqlx4kColumn.debug(prefix: String = ""): String = buildString {
    append("\n$prefix[Sqlx4kPgColumn]")
    append("\n${prefix}ordinal: $ordinal")
//    append("\n${prefix}name: ${name?.toKString() ?: "<EMPTY>"}")
//    append("\n${prefix}kind: ${kind?.toKString() ?: "<EMPTY>"}")
    append("\n${prefix}value: ${value?.toKString() ?: "<EMPTY>"}")
}

fun Sqlx4kResult.isError(): Boolean = error >= 0
fun Sqlx4kResult.toError(): SQLError {
    val code = SQLError.Code.entries[error]
    val message = error_message?.toKString()
    return SQLError(code, message)
}

fun Sqlx4kResult.getFirstRow(): Sqlx4kRow? =
    if (size >= 0) rows?.get(0) else null

fun Sqlx4kResult.throwIfError() {
    if (isError()) toError().ex()
}

fun CPointer<Sqlx4kResult>?.throwIfError() {
    use { it.throwIfError() }
}

private inline fun <T> CPointer<Sqlx4kResult>?.use(f: (it: Sqlx4kResult) -> T): T {
    return try {
        this?.pointed?.let { f(it) }
            ?: error("Could not extract the value from the raw pointer (null).")
    } finally {
        sqlx4k_free_result(this)
    }
}

fun CPointer<Sqlx4kResult>?.rowsAffectedOrError(): Long = use {
    it.throwIfError()
    it.rows_affected.toLong()
}

fun CPointer<Sqlx4kResult>?.tx(): Pair<CPointer<out CPointed>, ULong> = use { result ->
    result.throwIfError()
    result.tx!! to result.rows_affected
}

suspend inline fun sqlx(crossinline f: (c: CPointer<out CPointed>) -> Unit): CPointer<Sqlx4kResult>? =
    suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
        val ref: StableRef<Continuation<CPointer<Sqlx4kResult>?>> = StableRef.create(c)
        val ptr: CPointer<out CPointed> = ref.asCPointer()
        f(ptr)
    }
