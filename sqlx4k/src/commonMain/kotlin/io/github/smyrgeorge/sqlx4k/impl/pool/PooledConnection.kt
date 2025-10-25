@file:OptIn(ExperimentalAtomicApi::class)

package io.github.smyrgeorge.sqlx4k.impl.pool

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class PooledConnection(
    private val connection: Connection,
    private val pool: ConnectionPoolImpl
) : Connection by connection {
    private val mutex = Mutex()
    private var acquired = true
    private val released get() = !acquired
    private val createdAt: TimeMark = TIME_SOURCE.markNow()
    private var lastUsedAt: TimeMark = createdAt

    fun isReleased(): Boolean = released

    suspend fun acquire(): Connection {
        mutex.withLock {
            if (pool.closed.load()) {
                closeUnderlying()
                SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").ex()
            }

            acquired = true
            lastUsedAt = TIME_SOURCE.markNow()
        }
        return this
    }

    override suspend fun close(): Result<Unit> = runCatching {
        mutex.withLock {
            if (released) return@runCatching
            acquired = false
            lastUsedAt = TIME_SOURCE.markNow()
        }

        if (pool.closed.load()) {
            closeUnderlying()
            return@runCatching
        }

        if (isExpired()) {
            val minConnections = pool.options.minConnections ?: 0
            val wasClosed = closeUnderlyingIfAboveMinimum(minConnections)
            if (!wasClosed) {
                // At or below minimum, keep the expired connection (cleanup will replace later)
                // Enqueue back to idle using suspending send to properly wake waiters
                if (!pool.sendToIdle(this)) {
                    closeUnderlying()
                }
            }
        } else {
            if (!pool.sendToIdle(this)) {
                closeUnderlying()
            }
        }
    }

    fun isExpired(): Boolean {
        pool.options.maxLifetime?.let { maxLifetime -> if (createdAt.elapsedNow() >= maxLifetime) return true }
        pool.options.idleTimeout?.let { idleTimeout -> if (lastUsedAt.elapsedNow() >= idleTimeout) return true }
        return false
    }

    suspend fun closeUnderlying() {
        try {
            connection.close().getOrThrow()
        } catch (_: Exception) {
        } finally {
            pool.totalConnections.decrementAndFetch()
            pool.semaphore.release()
        }
    }

    suspend fun closeUnderlyingIfAboveMinimum(minConnections: Int): Boolean {
        var shouldClose = false
        pool.totalConnections.update {
            if (it > minConnections) {
                shouldClose = true
                it - 1
            } else {
                shouldClose = false
                it
            }
        }

        if (shouldClose) {
            try {
                connection.close().getOrThrow()
            } catch (_: Exception) {
            } finally {
                pool.semaphore.release()
            }
        }

        return shouldClose
    }

    companion object {
        private val TIME_SOURCE = TimeSource.Monotonic
    }
}
