@file:OptIn(ExperimentalAtomicApi::class)

package io.github.smyrgeorge.sqlx4k.impl.pool

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlin.concurrent.atomics.*
import kotlin.time.Duration.Companion.seconds

class ConnectionPoolImpl(
    val options: ConnectionPool.Options,
    private val connectionFactory: suspend () -> Connection,
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

    override fun poolSize(): Int = totalConnections.load()
    override fun poolIdleSize(): Int = idleCount.load()

    override suspend fun acquire(): Result<Connection> {
        return runCatching {
            if (closed.load()) SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").ex()
            if (options.acquireTimeout != null) withTimeout(options.acquireTimeout) { acquireConnection() }
            else acquireConnection()
        }.recoverCatching { error ->
            when (error) {
                is TimeoutCancellationException -> {
                    SQLError(
                        code = SQLError.Code.PoolTimedOut,
                        message = "Timed out waiting for connection after ${options.acquireTimeout}"
                    ).ex()
                }

                else -> throw error
            }
        }
    }

    private suspend fun acquireConnection(): Connection {
        while (true) {
            // Early closed check for fail-fast behavior
            if (closed.load()) {
                SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").ex()
            }

            // 1) Try to reuse an idle connection (drain expired ones)
            while (true) {
                val pooled = tryReceiveFromIdle() ?: break
                if (pooled.isExpired()) {
                    // Expired: close and continue draining
                    pooled.closeUnderlying()
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
                SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").ex()
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
                pooled.closeUnderlying()
            } else {
                return pooled.acquire()
            }
        }
        // Unreachable
    }

    override suspend fun close(): Result<Unit> = runCatching {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) {
            return@runCatching
        }

        cleanupJob.cancel()
        idleConnections.close()
        while (true) {
            val pooled = idleConnections.tryReceive().getOrNull() ?: break
            idleCount.decrementAndFetch()
            pooled.closeUnderlying()
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
                    pooled.closeUnderlying()
                    // No point continuing warmup if pool is closing
                    return
                }
            } catch (_: Exception) {
                // Clean up based on what succeeded
                if (pooled != null) {
                    // Connection was created but something failed, close it
                    try {
                        pooled.closeUnderlying()
                    } catch (_: Exception) {
                        // Ignore errors during cleanup
                    }
                } else if (acquired) {
                    // Only semaphore was acquired, release it
                    semaphore.release()
                }
                // TODO: Log error but continue warming up
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
                    val wasClosed = pooled.closeUnderlyingIfAboveMinimum(minConnections)
                    if (!wasClosed) {
                        // At or below minimum - keep the expired connection
                        // It will be used until a new connection can replace it
                        if (!sendToIdle(pooled)) {
                            // Pool is closing, cleanup the connection
                            pooled.closeUnderlying()
                        }
                    }
                } else {
                    // Connection still valid, return it
                    if (!sendToIdle(pooled)) {
                        // Pool is closing, cleanup the connection
                        pooled.closeUnderlying()
                    }
                }
            } catch (_: Exception) {
                // If anything fails, try to close the connection to prevent leaks
                try {
                    pooled.closeUnderlying()
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
            } catch (_: Exception) {
                // TODO: Log error but continue cleanup loop
            }
        }
    }

    companion object {
        private val CLEANUP_INTERVAL = 2.seconds
    }
}
