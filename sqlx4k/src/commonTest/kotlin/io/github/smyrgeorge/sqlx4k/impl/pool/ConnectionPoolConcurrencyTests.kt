package io.github.smyrgeorge.sqlx4k.impl.pool

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.pool.util.FakeConnection
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class ConnectionPoolConcurrencyTests {
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
    fun `High concurrency stress test - acquire and release with many coroutines`() = runBlocking {
        val pool = newPool(max = 10, acquireTimeout = 5.seconds)
        val iterations = 100
        val concurrency = 50

        val jobs = List(concurrency) {
            async {
                repeat(iterations) {
                    val conn = pool.acquire().getOrThrow()
                    // Simulate some work
                    delay(1)
                    conn.close().getOrThrow()
                }
            }
        }

        jobs.awaitAll()

        // All connections should be back in idle pool
        assertThat(pool.poolIdleSize()).isEqualTo(pool.poolSize())
        assertThat(pool.poolSize()).isGreaterThan(0)

        pool.close().getOrThrow()
    }

    @Test
    fun `Counter synchronization - idle count matches actual idle connections under concurrency`() = runBlocking {
        val pool = newPool(max = 10)
        val concurrency = 30

        // Rapidly acquire and release connections
        val jobs = List(concurrency) { index ->
            async {
                val conn = pool.acquire().getOrThrow()
                delay((index % 5).toLong()) // Varying delays
                conn.close().getOrThrow()
            }
        }

        jobs.awaitAll()

        // Give system a moment to stabilize
        delay(50)

        // Verify counters are consistent
        val poolSize = pool.poolSize()
        val idleSize = pool.poolIdleSize()

        assertThat(idleSize).isEqualTo(poolSize)
        assertThat(pool.poolSize()).isGreaterThan(0)

        pool.close().getOrThrow()
    }

    @Test
    fun `Coroutine cancellation during acquire does not leak resources`() = runBlocking {
        val pool = newPool(max = 2, acquireTimeout = 10.seconds)

        // Fill the pool
        val c1 = pool.acquire().getOrThrow()
        val c2 = pool.acquire().getOrThrow()

        // Start a coroutine that will be cancelled while waiting
        val job = async {
            pool.acquire().getOrThrow()
        }

        delay(50)
        job.cancel()

        // Release one connection
        c1.close().getOrThrow()

        // Pool should still work correctly
        val c3 = pool.acquire().getOrThrow()
        assertThat(pool.poolSize()).isEqualTo(2)

        c2.close().getOrThrow()
        c3.close().getOrThrow()
        pool.close().getOrThrow()
    }

    @Test
    fun `Concurrent cleanup and acquire operations maintain consistency`() = runBlocking {
        // Use longer idle timeout to avoid livelock from expired connections being churned
        val pool = newPool(min = 2, max = 10, idleTimeout = 500.milliseconds)

        // Wait for warmup
        repeat(10) {
            if (pool.poolIdleSize() >= 2) return@repeat
            delay(20)
        }

        // Create activity while cleanup is running
        val jobs = List(20) {
            async {
                repeat(10) {
                    val conn = pool.acquire().getOrThrow()
                    delay(5)
                    conn.close().getOrThrow()
                    delay(15) // Some connections will become idle
                }
            }
        }

        jobs.awaitAll()

        // Verify pool is in consistent state
        assertThat(pool.poolIdleSize()).isEqualTo(pool.poolSize())

        pool.close().getOrThrow()
    }

    @Test
    fun `Rapid acquire-release cycles maintain counter accuracy`() = runBlocking {
        val pool = newPool(max = 5)
        val iterations = 200

        repeat(iterations) {
            val conn = pool.acquire().getOrThrow()
            conn.close().getOrThrow()
        }

        // All connections should be idle
        assertThat(pool.poolIdleSize()).isEqualTo(pool.poolSize())
        assertThat(pool.poolSize()).isGreaterThan(0)

        pool.close().getOrThrow()
    }

    @Test
    fun `Interleaved acquire and release with cleanup maintains consistency`() = runBlocking {
        val pool = newPool(min = 2, max = 8, idleTimeout = 150.milliseconds)

        // Wait for initial warmup
        delay(100)

        // Create complex interleaving pattern
        val jobs = (1..30).map {
            async {
                val conn = pool.acquire().getOrThrow()
                delay((10..80L).random())
                conn.close().getOrThrow()
            }
        }

        jobs.awaitAll()
        delay(100) // Let things settle

        // Verify consistency
        val finalIdle = pool.poolIdleSize()
        val finalTotal = pool.poolSize()

        assertThat(finalIdle).isEqualTo(finalTotal)
        assertThat(finalTotal).isGreaterThan(0)

        pool.close().getOrThrow()
    }
}
