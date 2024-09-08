package io.github.smyrgeorge.sqlx4k.impl

import io.github.smyrgeorge.sqlx4k.ResultSet
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import sqlx4k.Sqlx4kResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalForeignApi::class)
suspend inline fun sqlx(crossinline f: (c: CPointer<out CPointed>) -> Unit): CPointer<Sqlx4kResult>? =
    suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
        val ref: StableRef<Continuation<CPointer<Sqlx4kResult>?>> = StableRef.create(c)
        val ptr: CPointer<out CPointed> = ref.asCPointer()
        f(ptr)
    }

fun Result<*>.errorOrNull(): ResultSet.Error? =
    exceptionOrNull() as? ResultSet.Error
