package io.github.smyrgeorge.sqlx4k.impl.pool

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.pool.util.FakeConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConnectionPoolLimitsTests {
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
        return ConnectionPoolImpl(options, ValueEncoderRegistry.EMPTY) {
            FakeConnection(nextId++).also(onCreate)
        }
    }

    @Test
    fun `Max connections and timeout enforced`() = runBlocking {
        val pool = newPool(max = 1, acquireTimeout = 150.milliseconds)

        val c1 = pool.acquire().getOrThrow()

        val e = assertFailsWith<SQLError> {
            // Should time out because max=1 and c1 is not released
            pool.acquire().getOrThrow()
        }
        assertThat(e.code).isEqualTo(SQLError.Code.PoolTimedOut)

        c1.close().getOrThrow()
        pool.close().getOrThrow()
    }

    @Test
    fun `Concurrent acquisitions respect max connections`() = runBlocking {
        val pool = newPool(max = 2, acquireTimeout = 1.seconds)

        val a1 = async { pool.acquire().getOrThrow() }
        val a2 = async { pool.acquire().getOrThrow() }
        val conns = awaitAll(a1, a2)

        assertThat(pool.poolSize()).isEqualTo(2)
        assertThat(pool.poolIdleSize()).isEqualTo(0)

        conns.forEach { it.close().getOrThrow() }
        assertThat(pool.poolIdleSize()).isEqualTo(2)

        pool.close().getOrThrow()
    }

    @Test
    fun `Acquire timeout with very short wait time fails quickly`() = runBlocking {
        val pool = newPool(max = 1, acquireTimeout = 50.milliseconds)

        val c1 = pool.acquire().getOrThrow()

        val e = assertFailsWith<SQLError> {
            pool.acquire().getOrThrow()
        }
        assertThat(e.code).isEqualTo(SQLError.Code.PoolTimedOut)

        c1.close().getOrThrow()
        pool.close().getOrThrow()
    }

    @Test
    fun `Multiple concurrent waiters all succeed when connections become available`() = runBlocking {
        val pool = newPool(max = 10, acquireTimeout = 2.seconds)

        // Fill the pool with 3 connections
        val held = List(3) { pool.acquire().getOrThrow() }

        // Start 10 waiters
        val waiters = List(10) {
            async {
                pool.acquire().getOrThrow()
            }
        }

        // Give waiters time to queue up
        delay(100)

        // Release connections one by one
        held.forEach { conn ->
            delay(50)
            conn.close().getOrThrow()
        }

        // All waiters should eventually succeed (some will get released connections, others will create new ones)
        val acquired = waiters.awaitAll()
        assertThat(acquired.size).isEqualTo(10)

        // Clean up
        acquired.forEach { it.close().getOrThrow() }
        pool.close().getOrThrow()
    }

    @Test
    fun `Min connections are created during warmup`() = runBlocking {
        val pool = newPool(min = 5, max = 10)

        // Give warmup time to complete
        delay(200)

        assertThat(pool.poolSize()).isEqualTo(5)
        assertThat(pool.poolIdleSize()).isEqualTo(5)

        pool.close().getOrThrow()
    }

    @Test
    fun `Pool can grow beyond min connections`() = runBlocking {
        val pool = newPool(min = 2, max = 5)

        delay(200) // Wait for warmup

        // Acquire more than min
        val conns = List(4) { pool.acquire().getOrThrow() }

        assertThat(pool.poolSize()).isEqualTo(4)
        assertThat(pool.poolIdleSize()).isEqualTo(0)

        conns.forEach { it.close().getOrThrow() }
        pool.close().getOrThrow()
    }

    @Test
    fun `Acquire without timeout blocks until connection available`() = runBlocking {
        val pool = newPool(max = 1, acquireTimeout = null)

        val c1 = pool.acquire().getOrThrow()

        val deferred = async {
            pool.acquire().getOrThrow()
        }

        delay(100) // Give time for acquire to block

        // Release first connection
        c1.close().getOrThrow()

        // Second acquire should now succeed
        val c2 = deferred.await()
        assertThat(c2).isNotNull()

        c2.close().getOrThrow()
        pool.close().getOrThrow()
    }

    @Test
    fun `Pool size never exceeds max connections`() = runBlocking {
        val pool = newPool(max = 5)

        // Try to acquire more than max concurrently
        repeat(100) {
            async { pool.acquire().getOrNull() }
        }

        delay(200)

        // Even with 100 attempts, pool size should never exceed max
        assertThat(pool.poolSize()).isEqualTo(5)
        pool.close().getOrThrow()
    }

    @Test
    fun `Multiple timeouts do not break the pool`() = runBlocking {
        val pool = newPool(max = 1, acquireTimeout = 50.milliseconds)

        val c1 = pool.acquire().getOrThrow()

        // Multiple concurrent acquire attempts should all timeout
        val failures = List(10) {
            async {
                runCatching { pool.acquire().getOrThrow() }
            }
        }.awaitAll().filter { it.isFailure }

        assertThat(failures.size).isEqualTo(10)

        // Pool should still work after timeouts
        c1.close().getOrThrow()

        val c2 = pool.acquire().getOrThrow()
        assertThat(c2).isNotNull()

        c2.close().getOrThrow()
        pool.close().getOrThrow()
    }

    @Test
    fun `Stress test - rapid acquire and release cycles`() = runBlocking {
        val pool = newPool(min = 2, max = 10, acquireTimeout = 2.seconds)

        delay(200) // Wait for warmup

        // 50 coroutines each doing 10 acquire/release cycles
        val jobs = List(50) {
            async {
                repeat(10) {
                    val conn = pool.acquire().getOrThrow()
                    delay(5) // Simulate some work
                    conn.close().getOrThrow()
                }
            }
        }

        jobs.awaitAll()

        // Pool should be in a valid state
        assertThat(pool.poolSize()).isEqualTo(pool.poolIdleSize())

        pool.close().getOrThrow()
    }

    @Test
    fun `Pool idle size matches pool size when all connections released`() = runBlocking {
        val pool = newPool(max = 5)

        val conns = List(5) { pool.acquire().getOrThrow() }
        assertThat(pool.poolSize()).isEqualTo(5)
        assertThat(pool.poolIdleSize()).isEqualTo(0)

        conns.forEach { it.close().getOrThrow() }

        delay(50) // Allow releases to complete

        assertThat(pool.poolSize()).isEqualTo(pool.poolIdleSize())

        pool.close().getOrThrow()
    }

    @Test
    fun `Acquire after pool close fails with PoolClosed error`() = runBlocking {
        val pool = newPool(max = 2)

        pool.close().getOrThrow()

        val e = assertFailsWith<SQLError> {
            pool.acquire().getOrThrow()
        }
        assertThat(e.code).isEqualTo(SQLError.Code.PoolClosed)
    }

    @Test
    fun `Concurrent acquire during pool close handles gracefully`() = runBlocking {
        val pool = newPool(max = 5)

        // Start multiple acquires
        val acquires = List(10) {
            async {
                runCatching { pool.acquire().getOrThrow() }
            }
        }

        delay(50)

        // Close pool while acquires are in progress
        pool.close()

        // All should either succeed or fail with PoolClosed
        val results = acquires.awaitAll()
        results.forEach { result ->
            if (result.isFailure) {
                val e = result.exceptionOrNull()
                assertThat(e is SQLError).isEqualTo(true)
                assertThat((e as SQLError).code).isEqualTo(SQLError.Code.PoolClosed)
            } else {
                // If it succeeded, connection should handle release gracefully
                result.getOrThrow().close()
            }
        }
    }

    @Test
    fun `Pool with max=1 serializes connection access`() = runBlocking {
        val accessOrder = mutableListOf<Int>()
        val mutex = Mutex()
        val pool = newPool(max = 1)

        val jobs = List(5) { index ->
            async {
                val conn = pool.acquire().getOrThrow()
                mutex.withLock {
                    accessOrder.add(index)
                }
                delay(50)
                conn.close().getOrThrow()
            }
        }

        jobs.awaitAll()

        // Should have exactly 5 accesses
        assertThat(accessOrder.size).isEqualTo(5)

        pool.close().getOrThrow()
    }

    @Test
    fun `Pool counters remain consistent after mixed success and failure`() = runBlocking {
        var failCount = 0
        val options = ConnectionPool.Options(minConnections = null, maxConnections = 5)
        val pool = ConnectionPoolImpl(options, ValueEncoderRegistry.EMPTY) {
            if (failCount++ < 2) {
                error("Simulated failure")
            } else {
                FakeConnection(nextId++)
            }
        }

        // Try to acquire 7 connections (first 2 should fail)
        val results = List(7) {
            async { pool.acquire() }
        }.awaitAll()

        val successful = results.filter { it.isSuccess }
        assertThat(successful.size).isEqualTo(5) // max connections = 5, but 2 failed first

        // Pool size should match successful acquisitions that are still held
        assertThat(pool.poolSize()).isEqualTo(successful.size)

        successful.forEach { it.getOrThrow().close().getOrThrow() }
        pool.close().getOrThrow()
    }

    @Test
    fun `Acquiring exactly max connections leaves no idle connections`() = runBlocking {
        val pool = newPool(max = 3)

        val conns = List(3) { pool.acquire().getOrThrow() }

        assertThat(pool.poolSize()).isEqualTo(3)
        assertThat(pool.poolIdleSize()).isEqualTo(0)

        conns.forEach { it.close().getOrThrow() }
        pool.close().getOrThrow()
    }

    @Test
    fun `Pool can handle immediate acquire after release`() = runBlocking {
        val pool = newPool(max = 1)

        repeat(20) {
            val conn = pool.acquire().getOrThrow()
            conn.close().getOrThrow()
        }

        assertThat(pool.poolSize()).isEqualTo(1)

        pool.close().getOrThrow()
    }
}
