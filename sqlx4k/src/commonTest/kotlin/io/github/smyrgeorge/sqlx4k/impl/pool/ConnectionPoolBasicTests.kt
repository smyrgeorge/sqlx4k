package io.github.smyrgeorge.sqlx4k.impl.pool

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.pool.util.FakeConnection
import kotlin.test.Test
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking

class ConnectionPoolBasicTests {
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
    fun `Acquire and release updates sizes and status`() = runBlocking {
        val pool = newPool(max = 1)

        val c1 = pool.acquire().getOrThrow() as PooledConnection
        assertThat(pool.poolSize()).isEqualTo(1)
        assertThat(pool.poolIdleSize()).isEqualTo(0)
        assertThat(c1.status).isEqualTo(Connection.Status.Open)

        c1.close().getOrThrow()
        assertThat(pool.poolSize()).isEqualTo(1)
        assertThat(pool.poolIdleSize()).isEqualTo(1)
        assertThat(c1.isReleased()).isTrue()

        pool.close().getOrThrow()
    }

    @Test
    fun `Connection is reused when not expired`() = runBlocking {
        val pool = newPool(max = 1)

        val c1 = pool.acquire().getOrThrow()
        val id1 = (c1.execute("id").getOrThrow())
        c1.close().getOrThrow()

        val c2 = pool.acquire().getOrThrow()
        val id2 = (c2.execute("id").getOrThrow())
        c2.close().getOrThrow()

        assertThat(id2).isEqualTo(id1)

        pool.close().getOrThrow()
    }

    @Test
    fun `Connection status updates on release`() = runBlocking {
        val pool = newPool(max = 1)

        val c = pool.acquire().getOrThrow() as PooledConnection
        assertThat(c.status).isEqualTo(Connection.Status.Open)

        c.close().getOrThrow()
        assertThat(c.isReleased()).isTrue()

        pool.close().getOrThrow()
    }

    @Test
    fun `Transaction can be started from pooled connection`() = runBlocking {
        val pool = newPool(max = 1)

        val c = pool.acquire().getOrThrow()
        val tx = c.begin().getOrThrow()
        assertThat(tx.status).isEqualTo(Transaction.Status.Open)

        tx.commit().getOrThrow()
        assertThat(tx.status).isEqualTo(Transaction.Status.Closed)

        c.close().getOrThrow()
        pool.close().getOrThrow()
    }
}
