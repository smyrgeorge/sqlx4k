@file:OptIn(ExperimentalAtomicApi::class)

package io.github.smyrgeorge.sqlx4k.impl.pool

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A coroutine-based connection pool implementation that manages database connections
 * with support for connection lifecycle management, size limits, and automatic cleanup.
 *
 * This implementation provides:
 * - Minimum and maximum connection limits
 * - Connection acquisition with timeout
 * - Idle timeout for unused connections
 * - Maximum lifetime for connections
 * - Background cleanup of expired connections
 * - Thread-safe operations using Kotlin coroutines
 *
 * @property connectionFactory A suspend function that creates new database connections
 * @property options Configuration options for the connection pool
 */
class ConnectionPoolImpl(
    private val connectionFactory: suspend () -> Connection,
    private val options: ConnectionPool.Options
) : ConnectionPool {

    /**
     * Channel holding idle connections available for reuse.
     * Uses UNLIMITED capacity to allow all idle connections to be queued.
     */
    private val idleConnections = Channel<PooledConnection>(Channel.UNLIMITED)

    /**
     * Semaphore to limit the maximum number of concurrent connections.
     * The number of available permits represents connections that can still be created.
     */
    private val semaphore = Semaphore(options.maxConnections)

    /**
     * Mutex for thread-safe access to pool state.
     */
    private val mutex: Mutex = Mutex()

    /**
     * Total number of connections currently managed by the pool (both idle and acquired).
     */
    @Volatile
    private var totalConnections: Int = 0

    /**
     * Count of idle connections. Updated atomically when connections are added/removed from idle pool.
     */
    @Volatile
    private var idleCount = 0

    /**
     * Flag indicating whether the pool has been closed.
     */
    private var closed: Boolean = false

    /**
     * Time source for tracking connection lifecycle.
     */
    private val timeSource = TimeSource.Monotonic

    /**
     * Coroutine scope for managing background jobs.
     */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Background job for cleaning up expired connections.
     */
    private val cleanupJob: Job

    init {
        // Start background cleanup job
        cleanupJob = scope.launch { cleanupLoop() }

        // Warmup minimum connections if specified
        options.minConnections?.let { min -> if (min > 0) scope.launch { warmup(min) } }
    }

    /**
     * Retrieves the current size of the connection pool.
     *
     * @return the number of connections currently in the pool (both idle and acquired)
     */
    override fun poolSize(): Int = totalConnections

    /**
     * Retrieves the current number of idle connections in the connection pool.
     *
     * @return the count of currently idle connections
     */
    override fun poolIdleSize(): Int = idleCount

    /**
     * Acquires a connection from the connection pool.
     *
     * This method attempts to:
     * 1. Reuse an idle connection if available
     * 2. Create a new connection if under the max limit
     * 3. Wait for a connection to become available if at max limit
     *
     * @return A Result containing a Connection if successful, or an error
     * @throws SQLError.Code.PoolClosed if the pool is closed
     * @throws SQLError.Code.PoolTimedOut if acquisition times out
     */
    override suspend fun acquire(): Result<Connection> = runCatching {
        // Check if pool is closed
        mutex.withLock { if (closed) SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").ex() }

        // Apply acquisition timeout if configured
        if (options.acquireTimeout != null) {
            withTimeout(options.acquireTimeout) { acquireConnection() }
        } else {
            acquireConnection()
        }
    }.recoverCatching { error ->
        when (error) {
            is TimeoutCancellationException -> {
                SQLError(
                    SQLError.Code.PoolTimedOut,
                    "Timed out waiting for connection after ${options.acquireTimeout}"
                ).ex()
            }

            is CancellationException -> throw error
            else -> throw error
        }
    }

    private suspend fun acquireConnection(): Connection {
        suspend fun handleValidPooled(pooled: PooledConnection): Connection {
            pooled.lastUsedAt = timeSource.markNow()
            val isClosed = mutex.withLock { closed }
            if (isClosed) {
                pooled.close()
                SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").ex()
            }
            return pooled
        }

        while (true) {
            // 1) Try to reuse an idle connection (drain expired ones)
            while (true) {
                val pooled = idleConnections.tryReceive().getOrNull() ?: break
                mutex.withLock { idleCount-- }
                if (pooled.isExpired()) {
                    // Expired: close and continue draining
                    pooled.close()
                } else {
                    return handleValidPooled(pooled)
                }
            }

            // 2) If we can create a new one without blocking, do it
            if (semaphore.tryAcquire()) {
                try {
                    val now = timeSource.markNow()
                    val newConnection = connectionFactory()
                    val pooled = PooledConnection(newConnection, now, now, this)
                    mutex.withLock { totalConnections++ }

                    // If pool got closed while we were creating this connection, do not hand it out
                    val isClosed = mutex.withLock { closed }
                    if (isClosed) {
                        pooled.close()
                        SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").ex()
                    }
                    return pooled
                } catch (e: Exception) {
                    // Release semaphore if connection creation failed
                    semaphore.release()
                    throw e
                }
            }

            // 3) We are at max capacity. Wait for an idle connection to be returned.
            mutex.withLock { if (closed) SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").ex() }
            val received = idleConnections.receiveCatching()
            if (received.isClosed) {
                // Channel closed (pool is closing/closed) — abort acquire
                SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").ex()
            }
            val pooled = received.getOrNull() ?: continue
            mutex.withLock { idleCount-- }
            if (pooled.isExpired()) {
                // Expired: close and loop to try again
                pooled.close()
            } else {
                return handleValidPooled(pooled)
            }
        }
        // Unreachable
    }

    /**
     * Closes the connection pool and releases all resources.
     *
     * This method performs the following steps:
     * - Prevents new connections from being acquired by marking the pool as closed.
     * - Cancels the cleanup job responsible for managing idle connections.
     * - Closes the idle connection queue, ensuring no more connections can be added.
     * - Drains and closes all idle connections in the pool.
     * - Ensures the idle connection counter is properly synchronized and clamped to zero.
     * - Cancels the internal operation scope of the pool.
     *
     * @return A `Result` indicating success or error during the closure process.
     */
    override suspend fun close(): Result<Unit> = runCatching {
        mutex.withLock {
            if (closed) return@runCatching
            closed = true
        }

        // Cancel cleanup job
        cleanupJob.cancel()

        // Close the idle channel to wake any waiters and prevent further sends
        idleConnections.close()

        // Close all idle connections
        while (true) {
            val pooled = idleConnections.tryReceive().getOrNull() ?: break
            // Adjust idle counter for each drained connection
            mutex.withLock { idleCount-- }
            pooled.close()
        }
        // Ensure idleCount cannot go negative due to races; clamp to 0
        mutex.withLock { if (idleCount < 0) idleCount = 0 }

        // Cancel scope
        scope.cancel()
    }

    private suspend fun cleanupLoop() {
        suspend fun cleanupExpiredConnections() {
            val temp = mutableListOf<PooledConnection>()
            val minConnections = options.minConnections ?: 0

            // Poll all idle connections
            while (true) {
                val pooled = idleConnections.tryReceive().getOrNull() ?: break
                // Track removal from idle queue for accurate idleCount
                mutex.withLock { idleCount-- }
                temp.add(pooled)
            }

            // Filter and return valid connections, close expired ones
            for (pooled in temp) {
                if (pooled.isExpired()) {
                    // Try to close if above minimum (atomically checked)
                    val wasClosed = pooled.closeIfAboveMinimum(minConnections)
                    if (!wasClosed) {
                        // Couldn't close because we're at or below minimum, keep the connection
                        idleConnections.send(pooled)
                        mutex.withLock { idleCount++ }
                    }
                } else {
                    // Connection still valid, keep it
                    idleConnections.send(pooled)
                    mutex.withLock { idleCount++ }
                }
            }
        }

        val cleanupInterval = 5.seconds
        while (true) {
            try {
                delay(cleanupInterval)
                cleanupExpiredConnections()
            } catch (_: CancellationException) {
                // Job was cancelled, exit loop
                break
            } catch (_: Exception) {
                // Log error but continue cleanup loop
                // In a production system, you'd use proper logging here
            }
        }
    }

    private suspend fun warmup(minConnections: Int) {
        repeat(minConnections) {
            try {
                semaphore.acquire()
                val now = timeSource.markNow()
                val connection = connectionFactory()
                val pooled = PooledConnection(connection, now, now, this)
                mutex.withLock {
                    totalConnections++
                    idleCount++
                }
                idleConnections.send(pooled)
            } catch (_: Exception) {
                semaphore.release()
                // Log error but continue warming up
                // In production, you'd use proper logging
            }
        }
    }

    private class PooledConnection(
        val connection: Connection,
        val createdAt: TimeMark,
        var lastUsedAt: TimeMark,
        private val pool: ConnectionPoolImpl
    ) : Connection by connection {
        private var mtx = Mutex()
        private var released = false
        override var status: Connection.Status = connection.status

        override suspend fun release(): Result<Unit> = runCatching {
            mtx.withLock {
                if (released) return@runCatching
                released = true
                status = Connection.Status.Released
            }

            // Read closed flag under lock, but perform actions outside to avoid deadlocks
            val isClosed = pool.mutex.withLock { pool.closed }
            if (isClosed) {
                // Pool is closed, close the connection outside of the mutex
                close()
                return@runCatching
            }

            lastUsedAt = pool.timeSource.markNow()

            // Check if connection is expired
            if (isExpired()) {
                // Connection is expired, try to close it only if we're above minimum
                val minConnections = pool.options.minConnections ?: 0
                val wasClosed = closeIfAboveMinimum(minConnections)
                if (!wasClosed) {
                    // At or below minimum, keep the expired connection (cleanup will replace later)
                    // Try to enqueue back to idle; if channel is closed, close the connection instead
                    val enqueued = try {
                        pool.idleConnections.trySend(this).isSuccess
                    } catch (_: Throwable) {
                        false
                    }
                    if (enqueued) {
                        pool.mutex.withLock { pool.idleCount++ }
                    } else {
                        // Pool closing/closed; ensure the connection is closed
                        close()
                    }
                }
            } else {
                // Connection is still valid, return to idle pool
                val enqueued = try {
                    pool.idleConnections.trySend(this).isSuccess
                } catch (_: Throwable) {
                    false
                }
                if (enqueued) {
                    pool.mutex.withLock { pool.idleCount++ }
                } else {
                    // Pool closing/closed; ensure the connection is closed
                    close()
                }
            }
        }

        fun isExpired(): Boolean {
            pool.options.maxLifetime?.let { maxLifetime -> if (createdAt.elapsedNow() >= maxLifetime) return true }
            pool.options.idleTimeout?.let { idleTimeout -> if (lastUsedAt.elapsedNow() >= idleTimeout) return true }
            return false
        }

        suspend fun close() {
            try {
                connection.release().getOrThrow()
            } catch (_: Exception) {
                // Ignore errors on close
            } finally {
                pool.mutex.withLock { pool.totalConnections-- }
                pool.semaphore.release()
            }
        }

        suspend fun closeIfAboveMinimum(minConnections: Int): Boolean {
            // Atomically check and decide whether to close
            val shouldClose = pool.mutex.withLock {
                if (pool.totalConnections > minConnections) {
                    // We're above minimum, mark for closing by decrementing now
                    pool.totalConnections--
                    true
                } else {
                    false
                }
            }

            if (shouldClose) {
                // Close the actual connection outside the lock
                try {
                    connection.release().getOrThrow()
                } catch (_: Exception) {
                    // Ignore errors on close
                } finally {
                    pool.semaphore.release()
                }
            }

            return shouldClose
        }
    }
}
