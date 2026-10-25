package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.SQLError
import io.r2dbc.pool.ConnectionPool as NativeR2dbcConnectionPool
import io.r2dbc.spi.Connection as NativeR2dbcConnection
import io.r2dbc.spi.R2dbcTimeoutException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import reactor.pool.PoolAcquireTimeoutException
import reactor.pool.PoolShutdownException

/** Runs [block], reporting a driver failure as the matching [SQLError]. */
internal inline fun <T> rethrowAsSQLError(block: () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        e.toSQLError().raise()
    }

internal fun <T> Publisher<T>.toMono(): Mono<T> {
    if (this is Mono<T>) return this
    error("Publisher is not a Mono: ${this::class.qualifiedName}")
}

internal suspend fun NativeR2dbcConnectionPool.acquire(): NativeR2dbcConnection {
    return try {
        create().awaitSingle()
    } catch (e: Exception) {
        when (e) {
            is PoolShutdownException -> SQLError(SQLError.Code.PoolClosed, e.message).raise()
            is PoolAcquireTimeoutException -> SQLError(SQLError.Code.PoolTimedOut, e.message).raise()
            // r2dbc-pool enforces the acquire timeout itself and reports it as an R2dbcTimeoutException.
            is R2dbcTimeoutException -> SQLError(SQLError.Code.PoolTimedOut, e.message).raise()
            else -> SQLError(SQLError.Code.Pool, e.message, e).raise()
        }
    }
}

/** Runs [block] on a connection borrowed from the pool and returns the connection afterwards. */
internal suspend inline fun <T> NativeR2dbcConnectionPool.withConnection(
    block: NativeR2dbcConnection.() -> T
): Result<T> = runCatching {
    val connection = acquire()
    try {
        rethrowAsSQLError { connection.block() }
    } finally {
        connection.close().toMono().awaitSingleOrNull()
    }
}

/**
 * Collects the flow of [block] on a connection borrowed from the pool. The connection is held for the whole
 * collection and returned when the flow completes, fails or is cancelled.
 */
internal fun <T> NativeR2dbcConnectionPool.flowWithConnection(
    block: NativeR2dbcConnection.() -> Flow<T>
): Flow<T> = flow {
    val connection = acquire()
    try {
        emitAll(connection.block())
    } finally {
        withContext(NonCancellable) { connection.close().toMono().awaitSingleOrNull() }
    }
}

/** Runs [block] while holding the lock, once [assertIsOpen] passes. */
internal suspend inline fun <T> Mutex.withLockCatching(
    assertIsOpen: () -> Unit,
    block: () -> T
): Result<T> = runCatching {
    withLock {
        assertIsOpen()
        rethrowAsSQLError(block)
    }
}

/** Collects the flow of [block] while holding the lock (for the whole collection), once [assertIsOpen] passes. */
internal fun <T> Mutex.flowWithLock(
    assertIsOpen: () -> Unit,
    block: () -> Flow<T>
): Flow<T> = flow {
    withLock {
        assertIsOpen()
        emitAll(block())
    }
}

/**
 * Ends a transaction with [block] (a commit or a rollback). The connection is returned to the pool afterwards
 * when [close] is set.
 */
internal suspend inline fun NativeR2dbcConnection.endTransaction(close: Boolean, block: NativeR2dbcConnection.() -> Unit) {
    try {
        rethrowAsSQLError { block() }
    } finally {
        if (close) close().toMono().awaitSingleOrNull()
    }
}
