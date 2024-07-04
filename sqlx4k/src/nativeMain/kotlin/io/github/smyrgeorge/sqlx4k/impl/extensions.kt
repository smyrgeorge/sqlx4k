package io.github.smyrgeorge.sqlx4k.impl

import io.github.smyrgeorge.sqlx4k.Driver.Companion.idx
import io.github.smyrgeorge.sqlx4k.Driver.Companion.map
import io.github.smyrgeorge.sqlx4k.Driver.Companion.mutexMap
import io.github.smyrgeorge.sqlx4k.Sqlx4k
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import librust_lib.Sqlx4kResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalForeignApi::class)
internal suspend inline fun sqlx(crossinline f: (idx: ULong) -> Unit): CPointer<Sqlx4kResult>? =
    suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
        val idx = runBlocking {
            // The [runBlocking] it totally fine at this level.
            // We only lock for a very short period of time, just to store in the HashMap.
            val idx = idx()
            mutexMap.withLock { map[idx] = c } // Store the [Continuation] object to the HashMap.
            idx
        }
        f(idx)
    }


inline fun Result<*>.errorOrNull(): Sqlx4k.Error =
    exceptionOrNull() as Sqlx4k.Error

