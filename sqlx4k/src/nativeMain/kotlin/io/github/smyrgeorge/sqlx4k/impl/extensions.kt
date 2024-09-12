@file:OptIn(ExperimentalForeignApi::class)

package io.github.smyrgeorge.sqlx4k.impl

import io.github.smyrgeorge.sqlx4k.ResultSet
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import sqlx4k.Sqlx4kResult
import sqlx4k.sqlx4k_free_result
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

fun Sqlx4kResult.isError(): Boolean = error >= 0
fun Sqlx4kResult.toError(): ResultSet.Error {
    val code = ResultSet.Error.Code.entries[error]
    val message = error_message?.toKString()
    return ResultSet.Error(code, message)
}

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

fun CPointer<Sqlx4kResult>?.rowsAffectedOrError(): ULong = use {
    it.throwIfError()
    it.rows_affected
}

private inline fun <T> Sqlx4kResult.map(f: ResultSet.Row.() -> T): List<T> {
    throwIfError()
    val rows = mutableListOf<T>()
    repeat(size) { index ->
        val scope = ResultSet.Row(this.rows!![index])
        val row = f(scope)
        rows.add(row)
    }
    return rows
}

fun <T> CPointer<Sqlx4kResult>?.map(f: ResultSet.Row.() -> T): List<T> =
    use { result -> result.map(f) }

fun CPointer<Sqlx4kResult>?.tx(): Pair<CPointer<out CPointed>, ULong> = use { result ->
    result.throwIfError()
    result.tx!! to result.rows_affected
}

fun <T> CPointer<Sqlx4kResult>?.txMap(f: ResultSet.Row.() -> T): Pair<CPointer<out CPointed>, List<T>> =
    use { result -> result.tx!! to result.map(f) }

suspend inline fun sqlx(crossinline f: (c: CPointer<out CPointed>) -> Unit): CPointer<Sqlx4kResult>? =
    suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
        val ref: StableRef<Continuation<CPointer<Sqlx4kResult>?>> = StableRef.create(c)
        val ptr: CPointer<out CPointed> = ref.asCPointer()
        f(ptr)
    }

fun Result<*>.errorOrNull(): ResultSet.Error? =
    exceptionOrNull() as? ResultSet.Error

