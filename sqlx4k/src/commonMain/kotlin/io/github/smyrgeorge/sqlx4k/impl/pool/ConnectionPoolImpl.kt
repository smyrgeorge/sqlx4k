@file:OptIn(ExperimentalAtomicApi::class)

package io.github.smyrgeorge.sqlx4k.impl.pool

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
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
        // Tracks a connection obtained by acquireConnection so it can be reclaimed if the successful
        // result is discarded while the coroutine unwinds — either acquireTimeout firing at the
        // withTimeout boundary or the caller's coroutine being canceled during/after acquire().
        val handoff = AtomicReference<PooledConnection?>(null)
        return runCatching {
            if (closed.load()) SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").raise()
            if (options.acquireTimeout != null) withTimeout(options.acquireTimeout) { acquireConnection(handoff) }
            else acquireConnection(handoff)
        }.recoverCatching { error ->
            // On any failure, if a connection was already handed off, it never reached the caller, so
            // close it to avoid leaking it (and its semaphore permit). closeInternal() is idempotent,
            // so this is safe even when the failing path already tore the connection down.
            handoff.exchange(null)?.let { orphan -> withContext(NonCancellable) { orphan.closeInternal() } }
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

    // Publishes the connection to the hand-off holder before activating it, so acquire()'s
    // cancellation cleanup can find and release it if this coroutine is cancelled during acquire().
    private suspend fun handOff(
        pooled: PooledConnection,
        handoff: AtomicReference<PooledConnection?>
    ): Connection {
        handoff.store(pooled)
        return pooled.acquire()
    }

    private suspend fun acquireConnection(handoff: AtomicReference<PooledConnection?>): Connection {
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
                    return handOff(pooled, handoff)
                }
            }

            // 2) If we can create a new one without blocking, do it
            if (semaphore.tryAcquire()) {
                // semaphoreOwned tracks whether WE must release the semaphore on failure.
                // Once PooledConnection is fully constructed and acquire() is about to be
                // called, the PooledConnection takes ownership: closeInternal() → doClose()
                // will release the semaphore. Releasing it again in the catch would cause a
                // double-release and an IllegalStateException from the Semaphore.
                var semaphoreOwned = true
                try {
                    val newConnection = connectionFactory()
                    val pooled = PooledConnection(newConnection, this)
                    totalConnections.incrementAndFetch()
                    semaphoreOwned = false // PooledConnection now owns the semaphore lifecycle
                    return handOff(pooled, handoff)
                } catch (e: Exception) {
                    if (semaphoreOwned) semaphore.release()
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
                return handOff(pooled, handoff)
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
     * later calls to this method will have no effect.
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
        // Reset idleCount to 0 unconditionally: after draining the channel, no idle
        // connections remain, and the pool is permanently closed, so the value only
        // matters for any final poolIdleSize() call.
        idleCount.store(0)
        scope.cancel()
    }

    internal suspend fun sendToIdle(connection: PooledConnection): Boolean {
        // Increment before sending: a receiver must never observe the connection in the channel
        // while idleCount still excludes it, which would let the counter go transiently negative.
        idleCount.incrementAndFetch()
        return try {
            idleConnections.send(connection)
            true
        } catch (_: Exception) {
            idleCount.decrementAndFetch()
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
                    // Connection was created, but something failed, close it
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
        // Limit how many we process in one cleanup cycle. Use at least 1 so single-connection pools
        // (where maxConnections / 2 == 0) still reclaim expired idle connections in the background.
        val maxBatchSize: Int = maxOf(1, options.maxConnections / 2)

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
