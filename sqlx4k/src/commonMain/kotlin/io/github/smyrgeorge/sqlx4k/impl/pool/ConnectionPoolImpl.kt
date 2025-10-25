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

class ConnectionPoolImpl(
    private val options: ConnectionPool.Options,
    private val connectionFactory: suspend () -> Connection,
) : ConnectionPool {
    private var closed = AtomicBoolean(false)
    private val idleCount = AtomicInt(0)
    private val totalConnections = AtomicInt(0)

    private val cleanupJob: Job
    private val semaphore = Semaphore(options.maxConnections)
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
                        SQLError.Code.PoolTimedOut,
                        "Timed out waiting for connection after ${options.acquireTimeout}"
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
                    pooled.close()
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

                    // Double-check pool wasn't closed during connection creation
                    if (closed.load()) {
                        pooled.close()
                        SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").ex()
                    }
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
                pooled.close()
            } else {
                return pooled.acquire()
            }
        }
        // Unreachable
    }

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
        scope.cancel()
    }

    private suspend fun sendToIdle(connection: PooledConnection): Boolean {
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
        repeat(minConnections) {
            var semaphoreAcquired = false
            var connectionCreated = false
            var pooledConnection: PooledConnection? = null

            try {
                semaphore.acquire()
                semaphoreAcquired = true
                pooledConnection = PooledConnection(connectionFactory(), this)
                connectionCreated = true

                totalConnections.incrementAndFetch()

                // Use atomic send operation - if it fails, rollback
                if (!sendToIdle(pooledConnection)) {
                    // Send failed (pool closing), close the connection
                    pooledConnection.close()
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
                // TODO: Log error but continue warming up
            }
        }
    }

    private suspend fun cleanup() {
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

    private suspend fun cleanupLoop() {
        while (true) {
            try {
                delay(CLEANUP_INTERVAL)
                cleanup()
            } catch (_: CancellationException) {
                // Job was cancelled, exit loop
                break
            } catch (_: Exception) {
                // TODO: Log error but continue cleanup loop
            }
        }
    }

    private class PooledConnection(
        private val connection: Connection,
        private val pool: ConnectionPoolImpl
    ) : Connection by connection {
        private val mutex = Mutex()
        private val createdAt: TimeMark = TIME_SOURCE.markNow()
        private var lastUsedAt: TimeMark = createdAt
        override var status: Connection.Status = connection.status // TODO: Check if this is correct
        private val released get() = status == Connection.Status.Released

        suspend fun acquire(): Connection {
            mutex.withLock {
                if (pool.closed.load()) {
                    close()
                    SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").ex()
                }

                status = Connection.Status.Acquired
                lastUsedAt = TIME_SOURCE.markNow()
            }
            return this
        }

        override suspend fun release(): Result<Unit> = runCatching {
            mutex.withLock {
                if (released) return@runCatching
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
        private val CLEANUP_INTERVAL = 2.seconds
        private val TIME_SOURCE = TimeSource.Monotonic
    }
}
