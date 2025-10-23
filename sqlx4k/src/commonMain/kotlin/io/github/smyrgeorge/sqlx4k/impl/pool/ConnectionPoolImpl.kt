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
import kotlin.concurrent.atomics.*
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

    private val idleCount = AtomicInt(0)
    private val totalConnections = AtomicInt(0)
    private var closed = AtomicBoolean(false)

    /**
     * Background job for cleaning up expired connections.
     */
    private val cleanupJob: Job

    init {
        // Start background cleanup job
        cleanupJob = POOL_SCOPE.launch { cleanupLoop() }

        // Warmup minimum connections if specified
        options.minConnections?.let { min -> if (min > 0) POOL_SCOPE.launch { warmup(min) } }
    }

    /**
     * Atomically sends a connection to the idle pool and increments the idle counter.
     * This ensures that the channel operation and counter update happen together,
     * preventing desynchronization between the two.
     *
     * @param connection The pooled connection to add to the idle pool
     * @return true if the connection was successfully added, false otherwise
     */
    private suspend fun sendToIdle(connection: PooledConnection): Boolean {
        return try {
            idleConnections.send(connection)
            idleCount.incrementAndFetch()
            true
        } catch (_: Exception) {
            // Send failed (channel closed or other error), don't increment counter
            false
        }
    }

    /**
     * Atomically receives a connection from the idle pool and decrements the idle counter.
     * This ensures that the channel operation and counter update happen together.
     *
     * @return The pooled connection if available, null otherwise
     */
    private fun tryReceiveFromIdle(): PooledConnection? {
        return idleConnections.tryReceive().getOrNull()?.also {
            idleCount.decrementAndFetch()
        }
    }

    /**
     * Retrieves the current size of the connection pool.
     *
     * @return the number of connections currently in the pool (both idle and acquired)
     */
    override fun poolSize(): Int = totalConnections.load()

    /**
     * Retrieves the current number of idle connections in the connection pool.
     *
     * @return the count of currently idle connections
     */
    override fun poolIdleSize(): Int = idleCount.load()

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
    override suspend fun acquire(): Result<Connection> {
        return runCatching {
            // Check if pool is closed
            if (closed.load()) SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").ex()

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
    }

    private suspend fun acquireConnection(): Connection {
        suspend fun handleValidPooled(pooled: PooledConnection): Connection {
            pooled.prepareForReuse()
            pooled.lastUsedAt = TIME_SOURCE.markNow()
            if (closed.load()) {
                pooled.close()
                SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").ex()
            }
            return pooled
        }

        while (true) {
            // 1) Try to reuse an idle connection (drain expired ones)
            while (true) {
                val pooled = tryReceiveFromIdle() ?: break
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
                    val now = TIME_SOURCE.markNow()
                    val newConnection = connectionFactory()
                    val pooled = PooledConnection(newConnection, now, now, this)
                    totalConnections.incrementAndFetch()

                    // If pool got closed while we were creating this connection, do not hand it out
                    if (closed.load()) {
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
            if (closed.load()) SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").ex()

            // Block until a connection becomes available
            val received = idleConnections.receiveCatching()
            if (received.isClosed) {
                // Channel closed (pool is closing/closed) — abort acquire
                SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").ex()
            }

            // Atomically decrement counter when we receive a connection
            val pooled = received.getOrNull()?.also {
                idleCount.decrementAndFetch()
            } ?: continue

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
        if (closed.load()) return@runCatching
        closed.store(true)

        // Cancel cleanup job
        cleanupJob.cancel()

        // Close the idle channel to wake any waiters and prevent further sends
        idleConnections.close()

        // Close all idle connections
        while (true) {
            val pooled = idleConnections.tryReceive().getOrNull() ?: break
            // Adjust idle counter for each drained connection
            idleCount.decrementAndFetch()
            pooled.close()
        }
        // Ensure idleCount cannot go negative due to races; clamp to 0
        idleCount.update { if (it < 0) 0 else it }

        // Cancel scope
        POOL_SCOPE.cancel()
    }

    private suspend fun cleanupLoop() {
        suspend fun cleanupExpiredConnections() {
            val temp = mutableListOf<PooledConnection>()
            val minConnections = options.minConnections ?: 0

            // Poll all idle connections using atomic wrapper
            while (true) {
                val pooled = tryReceiveFromIdle() ?: break
                temp.add(pooled)
            }

            // Filter and return valid connections, close expired ones
            for (pooled in temp) {
                if (pooled.isExpired()) {
                    // Try to close if above minimum (atomically checked)
                    val wasClosed = pooled.closeIfAboveMinimum(minConnections)
                    if (!wasClosed) {
                        // Couldn't close because we're at or below minimum, keep the connection
                        sendToIdle(pooled)
                    }
                } else {
                    // Connection still valid, keep it
                    sendToIdle(pooled)
                }
            }
        }

        while (true) {
            try {
                delay(CLEANUP_INTERVAL)
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
            var semaphoreAcquired = false
            var connectionCreated = false
            var pooledConnection: PooledConnection? = null

            try {
                semaphore.acquire()
                semaphoreAcquired = true

                val now = TIME_SOURCE.markNow()
                val connection = connectionFactory()
                val pooled = PooledConnection(connection, now, now, this)
                pooledConnection = pooled
                connectionCreated = true

                totalConnections.incrementAndFetch()

                // Use atomic send operation - if it fails, rollback
                if (!sendToIdle(pooled)) {
                    // Send failed (pool closing), close the connection
                    pooled.close()
                }
            } catch (_: Exception) {
                // Clean up based on what succeeded
                if (connectionCreated && pooledConnection != null) {
                    // Connection was created but something failed, close it
                    try {
                        pooledConnection.close()
                    } catch (_: Exception) {
                        // Ignore errors during cleanup
                    }
                } else if (semaphoreAcquired) {
                    // Only semaphore was acquired, release it
                    semaphore.release()
                }
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
        private var mutex = Mutex()
        private var released = false
        override var status: Connection.Status = connection.status

        /**
         * Prepares the connection for reuse after being acquired from the idle pool.
         * Resets the released flag and status to allow the connection to be released again.
         */
        suspend fun prepareForReuse() {
            mutex.withLock {
                released = false
                status = Connection.Status.Acquired
            }
        }

        override suspend fun release(): Result<Unit> = runCatching {
            mutex.withLock {
                if (released) return@runCatching
                released = true
                status = Connection.Status.Released
            }

            // Read closed flag under lock, but perform actions outside to avoid deadlocks
            if (pool.closed.load()) {
                // Pool is closed, close the connection outside of the mutex
                close()
                return@runCatching
            }

            lastUsedAt = TIME_SOURCE.markNow()

            // Check if connection is expired
            if (isExpired()) {
                // Connection is expired, try to close it only if we're above minimum
                val minConnections = pool.options.minConnections ?: 0
                val wasClosed = closeIfAboveMinimum(minConnections)
                if (!wasClosed) {
                    // At or below minimum, keep the expired connection (cleanup will replace later)
                    // Enqueue back to idle using suspending send to properly wake waiters
                    if (!pool.sendToIdle(this)) {
                        // Pool closing/closed; ensure the connection is closed
                        close()
                    }
                }
            } else {
                // Connection is still valid, return to idle pool using suspending send
                if (!pool.sendToIdle(this)) {
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
                pool.totalConnections.decrementAndFetch()
                pool.semaphore.release()
            }
        }

        suspend fun closeIfAboveMinimum(minConnections: Int): Boolean {
            // Atomically check and decide whether to close
            var shouldClose = false
            pool.totalConnections.update {
                if (it > minConnections) {
                    // We're above minimum, mark for closing by decrementing now
                    shouldClose = true
                    it - 1
                } else {
                    shouldClose = false
                    it
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

    companion object {
        private val CLEANUP_INTERVAL = 5.seconds
        private val TIME_SOURCE = TimeSource.Monotonic
        private val POOL_SCOPE = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }
}
