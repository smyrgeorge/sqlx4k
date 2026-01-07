@file:OptIn(ExperimentalAtomicApi::class)

package io.github.smyrgeorge.sqlx4k.impl.pool

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlin.concurrent.atomics.*
import kotlin.time.Duration.Companion.seconds

/**
 * Represents an implementation of a connection pool that manages a pool of connections and provides
 * methods to acquire and release connections efficiently while maintaining a minimum and maximum
 * number of connections based on the specified configurations.
 *
 * This implementation supports features such as:
 * - Setting a maximum and optionally a minimum number of connections.
 * - Automatic warmup to pre-establish connections up to the minimum count.
 * - Connection expiration detection and cleanup.
 * - Timeout handling during connection acquisition.
 * - Graceful shutdown to clean up resources when the pool is closed.
 *
 * @constructor Initializes the connection pool with the provided options, logger, and connection
 * factory.
 *
 * @param options Configuration options for the connection pool such as max and min connections.
 * @param encoders Optional registry of value encoders to use for encoding query parameters.
 * @param log Optional logger to record connection pool activities and errors.
 * @param connectionFactory Factory function to create new connections on demand.
 */
class ConnectionPoolImpl(
    val options: ConnectionPool.Options,
    val encoders: ValueEncoderRegistry,
    private val log: Logger? = null,
    private val connectionFactory: ConnectionFactory,
) : ConnectionPool {
    internal var closed = AtomicBoolean(false)
    internal val idleCount = AtomicInt(0)
    internal val totalConnections = AtomicInt(0)
    internal val semaphore = Semaphore(options.maxConnections)

    private val cleanupJob: Job
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val idleConnections = Channel<PooledConnection>(options.maxConnections)

    init {
        cleanupJob = scope.launch { cleanupLoop() }
        options.minConnections?.let { min -> if (min > 0) scope.launch { warmup(min) } }
    }

    /**
     * Retrieves the current size of the connection pool, indicating the total number of connections managed by the pool.
     *
     * @return The total count of connections in the pool, including both idle and in-use connections.
     */
    override fun poolSize(): Int = totalConnections.load()

    /**
     * Retrieves the current number of idle connections in the connection pool.
     *
     * @return The count of idle connections available in the pool.
     */
    override fun poolIdleSize(): Int = idleCount.load()

    /**
     * Acquires a connection from the connection pool.
     *
     * This method attempts to retrieve a connection from the pool while respecting the specified
     * acquisition timeout. If the pool is closed or the acquisition times out, an appropriate
     * error is returned.
     *
     * @return A [Result] containing the acquired [Connection] if successful, or an error if
     * the acquisition fails or the pool is closed.
     */
    override suspend fun acquire(): Result<Connection> {
        return runCatching {
            if (closed.load()) SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").raise()
            if (options.acquireTimeout != null) withTimeout(options.acquireTimeout) { acquireConnection() }
            else acquireConnection()
        }.recoverCatching { error ->
            when (error) {
                is TimeoutCancellationException -> {
                    SQLError(
                        code = SQLError.Code.PoolTimedOut,
                        message = "Timed out waiting for connection after ${options.acquireTimeout}",
                        cause = error
                    ).raise()
                }

                else -> throw error
            }
        }
    }

    private suspend fun acquireConnection(): Connection {
        while (true) {
            // Early closed check for fail-fast behavior
            if (closed.load()) {
                SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").raise()
            }

            // 1) Try to reuse an idle connection (drain expired ones)
            while (true) {
                val pooled = tryReceiveFromIdle() ?: break
                if (pooled.isExpired()) {
                    // Expired: close and continue draining
                    pooled.closeInternal()
                    // Yield to other coroutines to prevent CPU spinning
                    yield()
                } else {
                    return pooled.acquire()
                }
            }

            // 2) If we can create a new one without blocking, do it
            if (semaphore.tryAcquire()) {
                try {
                    val newConnection = connectionFactory()
                    val pooled = PooledConnection(newConnection, this)
                    totalConnections.incrementAndFetch()
                    return pooled.acquire()
                } catch (e: Exception) {
                    // Release semaphore if connection creation failed
                    semaphore.release()
                    throw e
                }
            }

            // 3) We are at max capacity. Wait for an idle connection to be returned.
            val received = idleConnections.receiveCatching()
            if (received.isClosed) {
                // Channel closed (pool is closing/closed) — abort acquire
                SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").raise()
            }

            // Atomically decrement counter when we receive a connection
            val pooled = received.getOrNull()?.also {
                idleCount.decrementAndFetch()
            }

            // If getOrNull() returned null (shouldn't happen with receiveCatching),
            // loop back to try again
            if (pooled == null) continue

            if (pooled.isExpired()) {
                // Expired: close and loop to try again
                pooled.closeInternal()
            } else {
                return pooled.acquire()
            }
        }
        // Unreachable
    }

    /**
     * Closes the connection pool and releases all associated resources.
     *
     * This method ensures that all idle connections are properly closed,
     * cleans up associated tasks, and updates internal states to reflect
     * that the pool is no longer active. If the pool is already closed,
     * subsequent calls to this method will have no effect.
     *
     * @return A [Result] indicating the success or failure of the close operation.
     */
    override suspend fun close(): Result<Unit> = runCatching {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) {
            return@runCatching
        }

        cleanupJob.cancel()
        idleConnections.close()
        while (true) {
            val pooled = idleConnections.tryReceive().getOrNull() ?: break
            idleCount.decrementAndFetch()
            pooled.closeInternal()
        }
        // Ensure idleCount cannot go negative due to races; clamp to 0
        idleCount.update { if (it < 0) 0 else it }
        scope.cancel()
    }

    internal suspend fun sendToIdle(connection: PooledConnection): Boolean {
        return try {
            idleConnections.send(connection)
            idleCount.incrementAndFetch()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun tryReceiveFromIdle(): PooledConnection? {
        return idleConnections.tryReceive().getOrNull()?.also {
            idleCount.decrementAndFetch()
        }
    }

    private suspend fun warmup(minConnections: Int) {
        if (closed.load()) return

        repeat(minConnections) {
            if (closed.load()) return

            var acquired = false
            var pooled: PooledConnection? = null

            try {
                semaphore.acquire()
                acquired = true

                // Check again after potentially blocking on semaphore
                if (closed.load()) {
                    semaphore.release()
                    return
                }

                pooled = PooledConnection(connectionFactory(), this)
                totalConnections.incrementAndFetch()

                if (!sendToIdle(pooled)) {
                    // Send failed (pool closing), close the connection
                    pooled.closeInternal()
                    // No point continuing warmup if pool is closing
                    return
                }
            } catch (e: Exception) {
                // Clean up based on what succeeded
                if (pooled != null) {
                    // Connection was created but something failed, close it
                    try {
                        pooled.closeInternal()
                    } catch (_: Exception) {
                        // Ignore errors during cleanup
                    }
                } else if (acquired) {
                    // Only semaphore was acquired, release it
                    semaphore.release()
                }

                log?.error("Failed to warm up connection: ${e.message}", e)
            }
        }
    }

    private suspend fun cleanup() {
        // Early exit if pool is closing/closed
        if (closed.load()) return

        val minConnections = options.minConnections ?: 0
        var processedCount = 0

        // Process connections incrementally without draining all at once
        // This allows other coroutines to acquire connections during cleanup
        val maxBatchSize: Int = options.maxConnections / 2 // Limit how many we process in one cleanup cycle

        while (processedCount < maxBatchSize) {
            val pooled = tryReceiveFromIdle() ?: break
            processedCount++

            try {
                if (pooled.isExpired()) {
                    // Try to close if above minimum (atomically checked)
                    val wasClosed = pooled.closeInternalIfAboveMinimum(minConnections)
                    if (!wasClosed) {
                        // At or below minimum - keep the expired connection
                        // It will be used until a new connection can replace it
                        if (!sendToIdle(pooled)) {
                            // Pool is closing, cleanup the connection
                            pooled.closeInternal()
                        }
                    }
                } else {
                    // Connection still valid, return it
                    if (!sendToIdle(pooled)) {
                        // Pool is closing, cleanup the connection
                        pooled.closeInternal()
                    }
                }
            } catch (_: Exception) {
                // If anything fails, try to close the connection to prevent leaks
                try {
                    pooled.closeInternal()
                } catch (_: Exception) {
                    // Ignore errors during emergency cleanup
                }
            }

            // Yield periodically to prevent CPU spinning
            if (processedCount % 10 == 0) {
                yield()
            }
        }
    }

    private suspend fun cleanupLoop() {
        while (true) {
            try {
                delay(CLEANUP_INTERVAL)
                cleanup()
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                log?.error("Failed to cleanup connections: ${e.message}", e)
            }
        }
    }

    companion object {
        private val CLEANUP_INTERVAL = 2.seconds
    }
}
