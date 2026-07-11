package io.github.smyrgeorge.sqlx4k.impl.pool

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEqualTo
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.pool.util.FakeConnection
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

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
        delay(170.milliseconds)

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
        delay(170.milliseconds)

        val c2 = pool.acquire().getOrThrow()
        val id2 = c2.execute("id").getOrThrow()
        c2.close().getOrThrow()

        assertThat(id2).isNotEqualTo(id1)

        pool.close().getOrThrow()
    }

    @Test
    fun `Background cleanup reclaims an idle expired connection in a max-1 pool`() = runBlocking {
        // Regression test for the cleanup batch size: with maxConnections == 1 the batch size used
        // to be maxConnections / 2 == 0, so the background cleanup loop was a no-op and an idle
        // expired connection lingered until the next acquire. It should now be reclaimed on its own.
        val pool = newPool(max = 1, idleTimeout = 100.milliseconds)

        val c1 = pool.acquire().getOrThrow()
        c1.close().getOrThrow() // now idle
        assertThat(pool.poolSize()).isEqualTo(1)

        // Wait for expiry (100ms) plus at least one cleanup cycle (CLEANUP_INTERVAL is 2s), without
        // ever calling acquire() again — the background loop must do the reclamation itself.
        var waited = 0
        while (pool.poolSize() > 0 && waited < 8000) {
            delay(200.milliseconds)
            waited += 200
        }

        assertThat(pool.poolSize()).isEqualTo(0)
        assertThat(pool.poolIdleSize()).isEqualTo(0)

        pool.close().getOrThrow()
    }

    @Test
    fun `Expired connections during concurrent release are handled correctly`() = runBlocking {
        val pool = newPool(max = 5, idleTimeout = 50.milliseconds)

        // Acquire and release connections
        val connections = List(5) { pool.acquire().getOrThrow() }
        connections.forEach { it.close().getOrThrow() }

        // Wait for them to expire
        delay(100.milliseconds)

        // Acquire again - should get fresh connections
        val newConnections = List(5) { pool.acquire().getOrThrow() }

        // Pool should be consistent
        assertThat(pool.poolSize()).isGreaterThan(0)
        assertThat(pool.poolIdleSize()).isEqualTo(0)

        newConnections.forEach { it.close().getOrThrow() }
        pool.close().getOrThrow()
    }
}
