@file:OptIn(ExperimentalForeignApi::class)

package io.github.smyrgeorge.sqlx4k.impl

import io.github.smyrgeorge.sqlx4k.ResultSet
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.toKString
import sqlx4k.Sqlx4kResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

fun Sqlx4kResult.isError(): Boolean = error >= 0
fun Sqlx4kResult.toError(): ResultSet.Error {
    val code = ResultSet.Error.Code.entries[error]
    val message = error_message?.toKString()
    return ResultSet.Error(code, message)
}

@OptIn(ExperimentalForeignApi::class)
suspend inline fun sqlx(crossinline f: (c: CPointer<out CPointed>) -> Unit): CPointer<Sqlx4kResult>? =
    suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
        val ref: StableRef<Continuation<CPointer<Sqlx4kResult>?>> = StableRef.create(c)
        val ptr: CPointer<out CPointed> = ref.asCPointer()
        f(ptr)
    }

fun Result<*>.errorOrNull(): ResultSet.Error? =
    exceptionOrNull() as? ResultSet.Error

