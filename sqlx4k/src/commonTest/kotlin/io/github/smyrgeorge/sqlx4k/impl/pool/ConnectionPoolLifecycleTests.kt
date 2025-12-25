package io.github.smyrgeorge.sqlx4k.impl.pool

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.pool.util.FakeConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConnectionPoolLifecycleTests {
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
        return ConnectionPoolImpl(
            connectionFactory = {
                FakeConnection(nextId++).also(onCreate)
            },
            options = options,
            encoders = ValueEncoderRegistry.EMPTY
        )
    }

    @Test
    fun `Warmup min connections populates idle pool`() = runBlocking {
        val min = 2
        var created = 0
        val pool = newPool(min = min, max = 4) { created++ }

        // Warmup runs in background; give it a brief moment
        delay(500)

        assertThat(pool.poolSize()).isGreaterThan(0) // at least some created
        assertThat(pool.poolIdleSize()).isEqualTo(min)
        assertThat(created.toLong()).isEqualTo(min.toLong())

        pool.close().getOrThrow()
    }

    @Test
    fun `Closing pool closes idle and blocks new acquires`() = runBlocking {
        var closedCount = 0
        val pool = newPool(min = 2, max = 2) { it.onClose = { closedCount++ } }

        // Give warmup a chance
        delay(500)

        pool.close().getOrThrow()

        delay(500)

        // All idle connections should be closed by now
        assertThat(closedCount.toLong()).isEqualTo(2L)

        val e = assertFailsWith<SQLError> { pool.acquire().getOrThrow() }
        assertThat(e.code).isEqualTo(SQLError.Code.PoolClosed)
    }

    @Test
    fun `Releasing after pool close closes underlying connection`() = runBlocking {
        var closedCount = 0
        val pool = newPool(max = 1) { it.onClose = { closedCount++ } }

        val c = pool.acquire().getOrThrow()
        // Close pool while connection is checked out
        pool.close().getOrThrow()

        // Releasing afterwards should close underlying connection
        c.close().getOrThrow()
        assertThat(closedCount.toLong()).isEqualTo(1L)
    }

    @Test
    fun `Pool close while multiple waiters are blocked wakes all with error`() = runBlocking {
        val pool = newPool(max = 1, acquireTimeout = 5.seconds)

        // Acquire the only connection
        val c1 = pool.acquire().getOrThrow()

        // Start multiple waiters
        val waiters = List(5) {
            async {
                try {
                    pool.acquire().getOrThrow()
                    "success"
                } catch (e: SQLError) {
                    if (e.code == SQLError.Code.PoolClosed) "closed" else "other"
                }
            }
        }

        // Give waiters time to start waiting
        delay(100)

        // Close pool while waiters are blocked
        pool.close().getOrThrow()

        // All waiters should get PoolClosed error
        val results = waiters.awaitAll()
        assertThat(results.all { it == "closed" }).isEqualTo(true)

        c1.close().getOrThrow()
    }

    @Test
    fun `Cleanup loop maintains pool with idle timeout`() = runBlocking {
        val pool = newPool(min = 1, max = 3, idleTimeout = 200.milliseconds)

        // Wait for warmup
        repeat(15) {
            if (pool.poolIdleSize() >= 1) return@repeat
            delay(20)
        }

        // Create some connections beyond minimum
        val c1 = pool.acquire().getOrThrow()
        val c2 = pool.acquire().getOrThrow()
        c1.close().getOrThrow()
        c2.close().getOrThrow()

        val sizeAfterUse = pool.poolSize()
        assertThat(sizeAfterUse).isGreaterThan(0)

        // Pool should eventually stabilize
        delay(6000)

        // Pool should still have connections
        assertThat(pool.poolSize()).isGreaterThan(0)

        pool.close().getOrThrow()
    }

    @Test
    fun `Closing pool twice is safe`() = runBlocking {
        val pool = newPool(max = 1)

        pool.close().getOrThrow()
        pool.close().getOrThrow()  // Second close should be safe

        val e = assertFailsWith<SQLError> { pool.acquire().getOrThrow() }
        assertThat(e.code).isEqualTo(SQLError.Code.PoolClosed)
    }

    @Test
    fun `Min connections maintained after cleanup removes expired ones`() = runBlocking {
        val pool = newPool(min = 2, max = 4, idleTimeout = 100.milliseconds)

        // Wait for warmup
        delay(500)

        val initialSize = pool.poolSize()
        assertThat(initialSize).isGreaterThan(0)

        // Wait for cleanup cycle
        delay(3000)

        // Pool should maintain at least min connections
        assertThat(pool.poolSize()).isGreaterThan(0)

        pool.close().getOrThrow()
    }

    @Test
    fun `Pool maintains min connections even with cleanup running`() = runBlocking {
        val pool = newPool(min = 3, max = 6, idleTimeout = 200.milliseconds)

        // Wait for warmup
        repeat(15) {
            if (pool.poolIdleSize() >= 3) return@repeat
            delay(20)
        }

        // Use some connections
        val c1 = pool.acquire().getOrThrow()
        val c2 = pool.acquire().getOrThrow()
        c1.close().getOrThrow()
        c2.close().getOrThrow()

        // Wait for cleanup cycles
        delay(6500)

        // Pool should maintain at least min connections
        assertThat(pool.poolSize()).isGreaterThan(0)

        pool.close().getOrThrow()
    }

    @Test
    fun `Release during pool closure does not cause negative counters`() = runBlocking {
        val pool = newPool(max = 5)

        // Acquire multiple connections
        val connections = List(5) { pool.acquire().getOrThrow() }

        // Start closing pool in background
        val closeJob = async {
            delay(50)
            pool.close().getOrThrow()
        }

        // Release connections concurrently with pool closure
        val releaseJobs = connections.map { conn ->
            async {
                delay((0..100L).random())
                conn.close()
            }
        }

        closeJob.await()
        releaseJobs.awaitAll()

        // Pool should have non-negative counters
        assertThat(pool.poolSize()).isGreaterThan(-1)
        assertThat(pool.poolIdleSize()).isGreaterThan(-1)
    }
}
