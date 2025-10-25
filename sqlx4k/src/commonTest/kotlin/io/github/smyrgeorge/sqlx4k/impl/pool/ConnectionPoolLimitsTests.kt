package io.github.smyrgeorge.sqlx4k.impl.pool

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
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
        return ConnectionPoolImpl(
            connectionFactory = {
                FakeConnection(nextId++).also(onCreate)
            },
            options = options
        )
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

        c1.release().getOrThrow()
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

        conns.forEach { it.release().getOrThrow() }
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

        c1.release().getOrThrow()
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
            conn.release().getOrThrow()
        }

        // All waiters should eventually succeed (some will get released connections, others will create new ones)
        val acquired = waiters.awaitAll()
        assertThat(acquired.size).isEqualTo(10)

        // Clean up
        acquired.forEach { it.release().getOrThrow() }
        pool.close().getOrThrow()
    }
}
