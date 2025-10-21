package io.github.smyrgeorge.sqlx4k.impl.pool

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEqualTo
import io.github.smyrgeorge.sqlx4k.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConnectionPoolImplTests {

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
    fun `Acquire and release updates sizes and status`() = runBlocking {
        val pool = newPool(max = 1)

        val c1 = pool.acquire().getOrThrow()
        assertThat(pool.poolSize()).isEqualTo(1)
        assertThat(pool.poolIdleSize()).isEqualTo(0)
        assertThat(c1.status).isEqualTo(Connection.Status.Acquired)

        c1.release().getOrThrow()
        assertThat(pool.poolSize()).isEqualTo(1)
        assertThat(pool.poolIdleSize()).isEqualTo(1)
        assertThat(c1.status).isEqualTo(Connection.Status.Released)

        pool.close().getOrThrow()
    }

    @Test
    fun `Connection is reused when not expired`() = runBlocking {
        val pool = newPool(max = 1)

        val c1 = pool.acquire().getOrThrow()
        val id1 = (c1.execute("id").getOrThrow())
        c1.release().getOrThrow()

        val c2 = pool.acquire().getOrThrow()
        val id2 = (c2.execute("id").getOrThrow())
        c2.release().getOrThrow()

        assertThat(id2).isEqualTo(id1)

        pool.close().getOrThrow()
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
    fun `Warmup min connections populates idle pool`() = runBlocking {
        val min = 2
        var created = 0
        val pool = newPool(min = min, max = 4) { created++ }

        // Warmup runs in background; give it a brief moment
        repeat(10) {
            if (pool.poolIdleSize() >= min && pool.poolSize() >= min) return@repeat
            delay(20)
        }

        assertThat(pool.poolSize()).isGreaterThan(0) // at least some created
        assertThat(pool.poolIdleSize()).isEqualTo(min)
        assertThat(created.toLong()).isEqualTo(min.toLong())

        pool.close().getOrThrow()
    }

    @Test
    fun `Idle timeout replaces connection on next acquire`() = runBlocking {
        val pool = newPool(max = 1, idleTimeout = 150.milliseconds)

        val c1 = pool.acquire().getOrThrow()
        val id1 = c1.execute("id").getOrThrow()
        c1.release().getOrThrow()

        // Let it become idle-expired
        delay(170)

        val c2 = pool.acquire().getOrThrow()
        val id2 = c2.execute("id").getOrThrow()
        c2.release().getOrThrow()

        // Because the first idle connection expired, a new underlying connection should be created
        assertThat(id2).isNotEqualTo(id1)

        pool.close().getOrThrow()
    }

    @Test
    fun `Max lifetime replaces connection even if recently used`() = runBlocking {
        val pool = newPool(max = 1, maxLifetime = 150.milliseconds)

        val c1 = pool.acquire().getOrThrow()
        val id1 = c1.execute("id").getOrThrow()
        c1.release().getOrThrow()

        // Wait for lifetime to elapse
        delay(170)

        val c2 = pool.acquire().getOrThrow()
        val id2 = c2.execute("id").getOrThrow()
        c2.release().getOrThrow()

        assertThat(id2).isNotEqualTo(id1)

        pool.close().getOrThrow()
    }

    @Test
    fun `Closing pool closes idle and blocks new acquires`() = runBlocking {
        var closedCount = 0
        val pool = newPool(min = 2, max = 2) { it.onClose = { closedCount++ } }

        // Give warmup a chance
        repeat(10) {
            if (pool.poolIdleSize() >= 2) return@repeat
            delay(20)
        }

        pool.close().getOrThrow()

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
        c.release().getOrThrow()
        assertThat(closedCount.toLong()).isEqualTo(1L)
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
    fun `Connection factory error does not leak semaphore permits`() = runBlocking {
        var attempts = 0
        val factory: suspend () -> Connection = {
            attempts++
            if (attempts == 1) error("Simulated factory failure")
            FakeConnection(nextId++)
        }

        val pool = ConnectionPoolImpl(factory, ConnectionPool.Options(null, 2, null, null, null))

        // First acquire should fail
        assertFailsWith<IllegalStateException> { pool.acquire().getOrThrow() }

        // Second acquire should succeed (semaphore was released)
        val c = pool.acquire().getOrThrow()
        assertThat(pool.poolSize()).isEqualTo(1)

        c.release().getOrThrow()
        pool.close().getOrThrow()
    }

    @Test
    fun `Multiple release calls on same connection are idempotent`() = runBlocking {
        val pool = newPool(max = 1)

        val c = pool.acquire().getOrThrow()
        c.release().getOrThrow()
        c.release().getOrThrow()  // Second release should be safe
        c.release().getOrThrow()  // Third release should be safe

        assertThat(pool.poolIdleSize()).isEqualTo(1)
        assertThat(pool.poolSize()).isEqualTo(1)

        pool.close().getOrThrow()
    }

    @Test
    fun `Both idle timeout and max lifetime set - max lifetime takes precedence`() = runBlocking {
        val pool = newPool(max = 1, idleTimeout = 500.milliseconds, maxLifetime = 100.milliseconds)

        val c1 = pool.acquire().getOrThrow()
        val id1 = c1.execute("id").getOrThrow()
        c1.release().getOrThrow()

        // Wait for max lifetime (shorter than idle timeout)
        delay(120)

        val c2 = pool.acquire().getOrThrow()
        val id2 = c2.execute("id").getOrThrow()
        c2.release().getOrThrow()

        assertThat(id2).isNotEqualTo(id1)

        pool.close().getOrThrow()
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

        c1.release().getOrThrow()
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
        c1.release().getOrThrow()
        c2.release().getOrThrow()

        val sizeAfterUse = pool.poolSize()
        assertThat(sizeAfterUse).isGreaterThan(0)

        // Pool should eventually stabilize
        delay(6000)

        // Pool should still have connections
        assertThat(pool.poolSize()).isGreaterThan(0)

        pool.close().getOrThrow()
    }

    @Test
    fun `Connection status updates on release`() = runBlocking {
        val pool = newPool(max = 1)

        val c = pool.acquire().getOrThrow()
        assertThat(c.status).isEqualTo(Connection.Status.Acquired)

        c.release().getOrThrow()
        assertThat(c.status).isEqualTo(Connection.Status.Released)

        pool.close().getOrThrow()
    }

    @Test
    fun `Warmup respects max connections limit`() = runBlocking {
        val pool = newPool(min = 5, max = 3)

        // Give warmup time to attempt creating connections
        delay(200)

        // Should not exceed max despite min being higher
        assertThat(pool.poolSize()).isEqualTo(3)

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
    fun `Min connections maintained after cleanup removes expired ones`() = runBlocking {
        val pool = newPool(min = 2, max = 4, idleTimeout = 100.milliseconds)

        // Wait for warmup
        repeat(10) {
            if (pool.poolIdleSize() >= 2) return@repeat
            delay(20)
        }

        val initialSize = pool.poolSize()
        assertThat(initialSize).isGreaterThan(0)

        // Wait for cleanup cycle
        delay(6000)

        // Pool should maintain at least min connections
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
    fun `Transaction can be started from pooled connection`() = runBlocking {
        val pool = newPool(max = 1)

        val c = pool.acquire().getOrThrow()
        val tx = c.begin().getOrThrow()
        assertThat(tx.status).isEqualTo(Transaction.Status.Open)

        tx.commit().getOrThrow()
        assertThat(tx.status).isEqualTo(Transaction.Status.Closed)

        c.release().getOrThrow()
        pool.close().getOrThrow()
    }

    private class FakeTransaction : Transaction {
        override var status: Transaction.Status = Transaction.Status.Open

        override suspend fun commit(): Result<Unit> {
            status = Transaction.Status.Closed
            return Result.success(Unit)
        }

        override suspend fun rollback(): Result<Unit> {
            status = Transaction.Status.Closed
            return Result.success(Unit)
        }

        override suspend fun execute(sql: String): Result<Long> = Result.success(0)
        override suspend fun execute(statement: Statement): Result<Long> = Result.success(0)
        override suspend fun fetchAll(sql: String): Result<ResultSet> =
            Result.success(ResultSet(emptyList(), null, ResultSet.Metadata(emptyList())))

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            Result.success(ResultSet(emptyList(), null, ResultSet.Metadata(emptyList())))

        override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
            Result.success(emptyList())
    }

    private class FakeConnection(val id: Long) : Connection {
        override var status: Connection.Status = Connection.Status.Acquired
        var onClose: (() -> Unit)? = null
        private var closed = false
        private var releases = 0
        private var begins = 0
        private var executes = 0L

        override suspend fun release(): Result<Unit> {
            // Treat pool close or direct close identically for tests
            if (!closed) {
                closed = true
                onClose?.invoke()
            }
            status = Connection.Status.Released
            releases++
            return Result.success(Unit)
        }

        override suspend fun begin(): Result<Transaction> {
            begins++
            return Result.success(FakeTransaction())
        }

        override suspend fun execute(sql: String): Result<Long> {
            return if (sql == "id") Result.success(id) else Result.success(++executes)
        }

        override suspend fun execute(statement: Statement): Result<Long> = Result.success(++executes)
        override suspend fun fetchAll(sql: String): Result<ResultSet> =
            Result.success(ResultSet(emptyList(), null, ResultSet.Metadata(emptyList())))

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            Result.success(ResultSet(emptyList(), null, ResultSet.Metadata(emptyList())))

        override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
            Result.success(emptyList())
    }
}
