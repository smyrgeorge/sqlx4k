@file:OptIn(ExperimentalAtomicApi::class)

package io.github.smyrgeorge.sqlx4k.sqlite.pool

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A physical connection owned by the pool. Callers never get it directly: every acquisition hands out a new
 * [Lease], so a caller that keeps a connection after closing it can never reach the connection again once the
 * pool has handed it to someone else.
 */
internal class PooledConnection(
    private val connection: Connection,
    private val pool: ConnectionPoolImpl
) {
    // Serializes the use of the physical connection: each statement, and the whole collection of a stream.
    private val mutex = Mutex()

    private val createdAt: TimeMark = TIME_SOURCE.markNow()

    // Read from background/cleanup coroutines, so it is @Volatile to guarantee cross-thread visibility.
    @Volatile
    private var lastUsedAt: TimeMark = createdAt

    // Guards the one-time physical teardown (count decrement + connection close + permit release),
    // so overlapping close paths — e.g., a pool-closed close() racing the acquire() cancellation
    // cleanup — can never double-release the semaphore or double-decrement the pool count.
    private val physicallyClosed = AtomicBoolean(false)

    fun isExpired(): Boolean {
        pool.options.maxLifetime?.let { maxLifetime -> if (createdAt.elapsedNow() >= maxLifetime) return true }
        pool.options.idleTimeout?.let { idleTimeout -> if (lastUsedAt.elapsedNow() >= idleTimeout) return true }
        return false
    }

    /** Hands the connection to a caller, as a [Lease] of its own. */
    fun acquire(): Connection {
        if (pool.closed.load()) {
            SQLError(SQLError.Code.PoolClosed, "Connection pool is closed").raise()
        }
        lastUsedAt = TIME_SOURCE.markNow()
        return Lease()
    }

    /** Returns the connection to the pool, or closes it if the pool is closed or the connection expired. */
    private suspend fun release() {
        lastUsedAt = TIME_SOURCE.markNow()

        if (pool.closed.load()) {
            closeInternal()
            return
        }

        if (isExpired()) {
            val minConnections = pool.options.minConnections ?: 0
            val wasClosed = closeInternalIfAboveMinimum(minConnections)
            if (!wasClosed) {
                // At or below the minimum: keep the expired connection; the next acquire replaces it.
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
            pool.releasePermit()
        }
    }

    /**
     * One acquisition of the connection. Closing it returns the connection to the pool; from then on it stays
     * closed, even while the pool hands the connection to another caller.
     */
    private inner class Lease : Connection {
        override val encoders: ValueEncoderRegistry = pool.encoders

        // Written under the mutex, read without it by callers checking the status.
        @Volatile
        override var status: Connection.Status = Connection.Status.Open
            private set

        override val transactionIsolationLevel: Transaction.IsolationLevel?
            get() = connection.transactionIsolationLevel

        override suspend fun close(): Result<Unit> = runCatching {
            val released = mutex.withLock {
                if (status == Connection.Status.Closed) return@withLock false
                status = Connection.Status.Closed
                true
            }
            if (released) release()
        }

        /** Runs [block] on the connection, while this lease is open and no one else uses the connection. */
        private suspend fun <T> guarded(block: suspend () -> Result<T>): Result<T> = runCatching {
            mutex.withLock {
                assertIsOpen()
                block().getOrThrow()
            }
        }

        override suspend fun execute(sql: String): Result<Long> = guarded { connection.execute(sql) }
        override suspend fun fetchAll(sql: String): Result<ResultSet> = guarded { connection.fetchAll(sql) }
        override suspend fun execute(statement: Statement): Result<Long> = guarded { connection.execute(statement) }
        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            guarded { connection.fetchAll(statement) }

        // The connection is held (its mutex locked) for the whole collection, like the wrapped one.
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> = flow {
            mutex.withLock {
                assertIsOpen()
                emitAll(connection.fetch(sql, fetchSize))
            }
        }

        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> = flow {
            mutex.withLock {
                assertIsOpen()
                emitAll(connection.fetch(statement, fetchSize))
            }
        }

        override suspend fun begin(): Result<Transaction> = guarded { connection.begin() }

        override suspend fun setTransactionIsolationLevel(level: Transaction.IsolationLevel): Result<Unit> =
            guarded { connection.setTransactionIsolationLevel(level) }
    }

    companion object {
        private val TIME_SOURCE = TimeSource.Monotonic
    }
}
