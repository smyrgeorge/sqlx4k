@file:Suppress("SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite.pool

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isLessThan
import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.sqlite.pool.util.FakeConnection
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class ConnectionPoolRegressionTests {

    private fun options(max: Int, maxLifetime: kotlin.time.Duration? = null) =
        ConnectionPool.Options(null, max, 2.seconds, null, maxLifetime)

    @Test
    fun `a waiter gets a new connection when a released connection is closed for its lifetime`() = runBlocking {
        var nextId = 1L
        val pool = ConnectionPoolImpl(options(max = 1, maxLifetime = 100.milliseconds), ValueEncoderRegistry.EMPTY) {
            FakeConnection(nextId++)
        }
        val held = pool.acquire().getOrThrow()
        val waiter = async { pool.acquire() }
        delay(150.milliseconds)

        // Past its lifetime, the connection is closed on release instead of returning to the idle queue: that
        // frees capacity, and the waiter must use it right away rather than wait for its acquire timeout.
        val released = TimeSource.Monotonic.markNow()
        held.close().getOrThrow()
        val cn = waiter.await().getOrThrow()
        assertThat(released.elapsedNow()).isLessThan(1.seconds)

        cn.close().getOrThrow()
        pool.close().getOrThrow()
    }

    @Test
    fun `a waiter gets a new connection when creating another one fails`() = runBlocking {
        var calls = 0
        val pool = ConnectionPoolImpl(options(max = 2), ValueEncoderRegistry.EMPTY) {
            calls++
            if (calls == 2) {
                delay(200.milliseconds)
                error("cannot connect")
            }
            FakeConnection(calls.toLong())
        }
        val held = pool.acquire().getOrThrow()
        val failing = async { pool.acquire() }
        delay(50.milliseconds)
        val waiter = async { pool.acquire() } // the pool is at capacity while the second connection is created

        assertThat(failing.await()).isFailure()
        val failed = TimeSource.Monotonic.markNow()
        val cn = waiter.await().getOrThrow()
        assertThat(failed.elapsedNow()).isLessThan(1.seconds)

        cn.close().getOrThrow()
        held.close().getOrThrow()
        pool.close().getOrThrow()
    }

    @Test
    fun `a released connection stays closed while its connection is reused`() = runBlocking {
        var nextId = 1L
        val pool = ConnectionPoolImpl(options(max = 1), ValueEncoderRegistry.EMPTY) { FakeConnection(nextId++) }
        val first = pool.acquire().getOrThrow()
        first.close().getOrThrow()
        val second = pool.acquire().getOrThrow() // reuses the underlying connection

        assertThat(first.status).isEqualTo(Connection.Status.Closed)
        assertThat(first.execute("id")).isFailure()
        assertThat(second.execute("id").getOrThrow()).isEqualTo(1L)

        second.close().getOrThrow()
        pool.close().getOrThrow()
    }
}
