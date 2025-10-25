package io.github.smyrgeorge.sqlx4k.impl.pool

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.pool.util.FakeConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ConnectionPoolErrorHandlingTests {
    // Simple incremental id to distinguish FakeConnection instances
    private var nextId = 1L

    private fun newPool(
        min: Int? = null,
        max: Int = 2,
        acquireTimeout: Duration? = null,
        idleTimeout: Duration? = null,
        maxLifetime: Duration? = null,
        onCreate: (FakeConnection) -> Unit = {}
    ): ConnectionPoolImpl {
        val options = ConnectionPool.Options(min, max, acquireTimeout, idleTimeout, maxLifetime)
        return ConnectionPoolImpl(options) {
            FakeConnection(nextId++).also(onCreate)
        }
    }

    @Test
    fun `Connection factory error does not leak semaphore permits`() = runBlocking {
        var attempts = 0
        val factory: suspend () -> Connection = {
            attempts++
            if (attempts == 1) error("Simulated factory failure")
            FakeConnection(nextId++)
        }

        val pool = ConnectionPoolImpl(ConnectionPool.Options(null, 2), factory)

        // First acquire should fail
        assertFailsWith<IllegalStateException> { pool.acquire().getOrThrow() }

        // Second acquire should succeed (semaphore was released)
        val c = pool.acquire().getOrThrow()
        assertThat(pool.poolSize()).isEqualTo(1)

        c.close().getOrThrow()
        pool.close().getOrThrow()
    }

    @Test
    fun `Multiple release calls on same connection are idempotent`() = runBlocking {
        val pool = newPool(max = 1)

        val c = pool.acquire().getOrThrow()
        c.close().getOrThrow()
        c.close().getOrThrow()  // Second release should be safe
        c.close().getOrThrow()  // Third release should be safe

        assertThat(pool.poolIdleSize()).isEqualTo(1)
        assertThat(pool.poolSize()).isEqualTo(1)

        pool.close().getOrThrow()
    }

    @Test
    fun `Warmup failure does not corrupt pool state`() = runBlocking {
        var attempts = 0
        val factory: suspend () -> Connection = {
            attempts++
            if (attempts <= 2) {
                delay(10)
                error("Warmup connection $attempts failed")
            }
            FakeConnection(nextId++)
        }

        val options = ConnectionPool.Options(minConnections = 3, maxConnections = 5)
        val pool = ConnectionPoolImpl(options, factory)

        // Give warmup time to run (some will fail)
        delay(500)

        // Pool should still be functional despite warmup failures
        val conn = pool.acquire().getOrThrow()
        assertThat(pool.poolSize()).isGreaterThan(0)

        conn.close().getOrThrow()
        pool.close().getOrThrow()
    }

    @Test
    fun `Acquire after timeout releases semaphore permit`() = runBlocking {
        val pool = newPool(max = 1, acquireTimeout = 50.milliseconds)

        val c1 = pool.acquire().getOrThrow()

        // This should timeout
        assertFailsWith<SQLError> {
            pool.acquire().getOrThrow()
        }

        // Release the connection
        c1.close().getOrThrow()

        // Should be able to acquire again
        val c2 = pool.acquire().getOrThrow()
        assertThat(pool.poolSize()).isEqualTo(1)

        c2.close().getOrThrow()
        pool.close().getOrThrow()
    }

    @Test
    fun `Connection factory exception during warmup does not prevent pool operation`() = runBlocking {
        var created = 0
        val factory: suspend () -> Connection = {
            created++
            if (created <= 1) {
                error("First connection failed")
            }
            FakeConnection(nextId++)
        }

        val options = ConnectionPool.Options(minConnections = 3, maxConnections = 5)
        val pool = ConnectionPoolImpl(options, factory)

        // Give warmup time
        delay(500)

        // Pool should still be usable
        val conn = pool.acquire().getOrThrow()
        conn.close().getOrThrow()

        pool.close().getOrThrow()
    }
}
