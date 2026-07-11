@file:OptIn(ExperimentalAtomicApi::class)

package io.github.smyrgeorge.sqlx4k.impl.pool

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class PooledConnection(
    private val connection: Connection,
    private val pool: ConnectionPoolImpl
) : Connection {
    override val encoders: ValueEncoderRegistry = pool.encoders
    private val mutex = Mutex()

    // Read without the mutex from isExpired()/isReleased() on background/cleanup coroutines, so both
    // are @Volatile to guarantee cross-thread visibility of writes made under the mutex.
    @Volatile
    private var acquired = true
    private val released get() = !acquired
    private val createdAt: TimeMark = TIME_SOURCE.markNow()

    @Volatile
    private var lastUsedAt: TimeMark = createdAt

    // Guards the one-time physical teardown (count decrement + connection close + semaphore release),
    // so overlapping close paths — e.g., a pool-closed close() racing the acquire() cancellation
    // cleanup — can never double-release the semaphore or double-decrement the pool count.
    private val physicallyClosed = AtomicBoolean(false)

    override var status: Connection.Status = Connection.Status.Open
    override val transactionIsolationLevel: Transaction.IsolationLevel? get() = connection.transactionIsolationLevel

    fun isReleased(): Boolean = released

    fun isExpired(): Boolean {
        pool.options.maxLifetime?.let { maxLifetime -> if (createdAt.elapsedNow() >= maxLifetime) return true }
        pool.options.idleTimeout?.let { idleTimeout -> if (lastUsedAt.elapsedNow() >= idleTimeout) return true }
        return false
    }

    suspend fun acquire(): Connection {
        // Determine under the lock whether we need to close, but do the actual I/O
        // (closeInternal → connection.close()) *outside* the lock so we don't hold the
        // mutex across a potentially long network operation.
        val shouldClose = mutex.withLock {
            if (pool.closed.load()) {
                true
            } else {
                acquired = true
                lastUsedAt = TIME_SOURCE.markNow()
                status = Connection.Status.Open
                false
            }
        }
        if (shouldClose) {
            closeInternal()
            SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").raise()
        }
        return this
    }

    override suspend fun close(): Result<Unit> = runCatching {
        mutex.withLock {
            if (status == Connection.Status.Closed) return@runCatching
            if (released) return@runCatching
            acquired = false
            lastUsedAt = TIME_SOURCE.markNow()
            status = Connection.Status.Closed
        }

        if (pool.closed.load()) {
            closeInternal()
            return@runCatching
        }

        if (isExpired()) {
            val minConnections = pool.options.minConnections ?: 0
            val wasClosed = closeInternalIfAboveMinimum(minConnections)
            if (!wasClosed) {
                // At or below minimum, keep the expired connection (cleanup will replace later)
                // Enqueue back to idle using suspending send to properly wake waiters
                if (!pool.sendToIdle(this)) {
                    closeInternal()
                }
            }
        } else {
            if (!pool.sendToIdle(this)) {
                closeInternal()
            }
        }
    }

    internal suspend fun closeInternal() {
        // Claim the teardown first so a second closeInternal() (e.g. from acquire()'s cancellation
        // cleanup) neither decrements the count twice nor releases the semaphore twice.
        if (!physicallyClosed.compareAndSet(expectedValue = false, newValue = true)) return
        pool.totalConnections.decrementAndFetch()
        releasePhysical()
    }

    internal suspend fun closeInternalIfAboveMinimum(minConnections: Int): Boolean {
        if (physicallyClosed.load()) return false
        // Atomically decide-and-decrement: only tear down while strictly above the minimum. The
        // decrement and the decision are a single CAS, so the count can never be observed/acted upon
        // inconsistently (the previous AtomicInt.update captured a flag whose value could survive a
        // retry, closing a connection without decrementing).
        while (true) {
            val count = pool.totalConnections.load()
            if (count <= minConnections) return false
            if (pool.totalConnections.compareAndSet(count, count - 1)) {
                if (physicallyClosed.compareAndSet(expectedValue = false, newValue = true)) {
                    releasePhysical()
                    return true
                }
                // Raced with another close path that already tore down (and decremented); undo ours.
                pool.totalConnections.incrementAndFetch()
                return false
            }
        }
    }

    private suspend fun releasePhysical() {
        try {
            connection.close().getOrThrow()
        } catch (_: Exception) {
        } finally {
            pool.semaphore.release()
        }
    }

    override suspend fun execute(sql: String): Result<Long> = runCatching {
        mutex.withLock {
            assertIsOpen()
            return connection.execute(sql)
        }
    }

    override suspend fun fetchAll(sql: String): Result<ResultSet> = runCatching {
        return mutex.withLock {
            assertIsOpen()
            connection.fetchAll(sql)
        }
    }

    override suspend fun execute(statement: Statement): Result<Long> = runCatching {
        mutex.withLock {
            assertIsOpen()
            return connection.execute(statement)
        }
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> = runCatching {
        return mutex.withLock {
            assertIsOpen()
            connection.fetchAll(statement)
        }
    }

    override suspend fun begin(): Result<Transaction> = runCatching {
        mutex.withLock {
            assertIsOpen()
            return connection.begin()
        }
    }

    override suspend fun setTransactionIsolationLevel(level: Transaction.IsolationLevel): Result<Unit> =
        connection.setTransactionIsolationLevel(level)

    companion object {
        private val TIME_SOURCE = TimeSource.Monotonic
    }
}
