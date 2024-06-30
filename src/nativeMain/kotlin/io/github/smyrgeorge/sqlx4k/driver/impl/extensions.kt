package io.github.smyrgeorge.sqlx4k.driver.impl

import io.github.smyrgeorge.sqlx4k.driver.Driver.Companion.idx
import io.github.smyrgeorge.sqlx4k.driver.Driver.Companion.map
import io.github.smyrgeorge.sqlx4k.driver.Driver.Companion.mutexMap
import io.github.smyrgeorge.sqlx4k.driver.Transaction.Companion.arr
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import librust_lib.Sqlx4kResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalForeignApi::class)
suspend inline fun sqlx(crossinline f: (idx: ULong) -> Unit): CPointer<Sqlx4kResult>? =
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

@OptIn(ExperimentalForeignApi::class)
suspend inline fun sqlx(id: Int, crossinline f: () -> Unit): CPointer<Sqlx4kResult>? =
    suspendCoroutine { c: Continuation<CPointer<Sqlx4kResult>?> ->
        arr[id] = c
        f()
    }
