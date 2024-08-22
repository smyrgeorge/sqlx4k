package io.github.smyrgeorge.sqlx4k.postgres.impl

import io.github.smyrgeorge.sqlx4k.postgres.Sqlx4k
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import librust_lib.Sqlx4kResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalForeignApi::class)
internal suspend inline fun sqlx(crossinline f: (c: CPointer<out CPointed>) -> Unit): CPointer<Sqlx4kResult>? =
    suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
        val ref: StableRef<Continuation<CPointer<Sqlx4kResult>?>> = StableRef.create(c)
        val ptr: CPointer<out CPointed> = ref.asCPointer()
        f(ptr)
    }

fun Result<*>.errorOrNull(): Sqlx4k.Error? =
    exceptionOrNull() as? Sqlx4k.Error

