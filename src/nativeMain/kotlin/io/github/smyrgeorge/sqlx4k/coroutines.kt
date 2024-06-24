package io.github.smyrgeorge.sqlx4k

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

@Suppress("unused")
suspend fun <A, B> Iterable<A>.mapParallel(context: CoroutineContext = Dispatchers.IO, f: suspend (A) -> B): List<B> =
    withContext(context) { map { async { f(it) } }.awaitAll() }

suspend fun <A> Iterable<A>.forEachParallel(context: CoroutineContext = Dispatchers.IO, f: suspend (A) -> Unit): Unit =
    withContext(context) { map { async { f(it) } }.awaitAll() }

