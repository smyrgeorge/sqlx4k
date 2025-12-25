package io.github.smyrgeorge.sqlx4k.impl.pool

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEqualTo
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.pool.util.FakeConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ConnectionPoolExpirationTests {

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
    fun `Idle timeout replaces connection on next acquire`() = runBlocking {
        val pool = newPool(max = 1, idleTimeout = 150.milliseconds)

        val c1 = pool.acquire().getOrThrow()
        val id1 = c1.execute("id").getOrThrow()
        c1.close().getOrThrow()

        // Let it become idle-expired
        delay(170)

        val c2 = pool.acquire().getOrThrow()
        val id2 = c2.execute("id").getOrThrow()
        c2.close().getOrThrow()

        // Because the first idle connection expired, a new underlying connection should be created
        assertThat(id2).isNotEqualTo(id1)

        pool.close().getOrThrow()
    }

    @Test
    fun `Max lifetime replaces connection even if recently used`() = runBlocking {
        val pool = newPool(max = 1, maxLifetime = 150.milliseconds)

        val c1 = pool.acquire().getOrThrow()
        val id1 = c1.execute("id").getOrThrow()
        c1.close().getOrThrow()

        // Wait for lifetime to elapse
        delay(170)

        val c2 = pool.acquire().getOrThrow()
        val id2 = c2.execute("id").getOrThrow()
        c2.close().getOrThrow()

        assertThat(id2).isNotEqualTo(id1)

        pool.close().getOrThrow()
    }

    @Test
    fun `Expired connections during concurrent release are handled correctly`() = runBlocking {
        val pool = newPool(max = 5, idleTimeout = 50.milliseconds)

        // Acquire and release connections
        val connections = List(5) { pool.acquire().getOrThrow() }
        connections.forEach { it.close().getOrThrow() }

        // Wait for them to expire
        delay(100)

        // Acquire again - should get fresh connections
        val newConnections = List(5) { pool.acquire().getOrThrow() }

        // Pool should be consistent
        assertThat(pool.poolSize()).isGreaterThan(0)
        assertThat(pool.poolIdleSize()).isEqualTo(0)

        newConnections.forEach { it.close().getOrThrow() }
        pool.close().getOrThrow()
    }
}
