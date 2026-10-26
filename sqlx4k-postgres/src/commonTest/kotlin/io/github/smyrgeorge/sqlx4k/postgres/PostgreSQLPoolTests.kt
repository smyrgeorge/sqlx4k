@file:Suppress("SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.postgres

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isSuccess
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PostgreSQLPoolTests {

    private val opened = mutableListOf<IPostgresSQL>()

    private fun pool(maxConnections: Int, acquireTimeout: Duration = 5.seconds): IPostgresSQL {
        val options = ConnectionPool.Options.builder()
            .maxConnections(maxConnections)
            .acquireTimeout(acquireTimeout)
            .build()
        return testPostgreSQL(options = options).also { opened += it }
    }

    // Some tests close their pool themselves, so close failures are ignored.
    @AfterTest
    fun tearDown(): Unit = runBlocking { opened.forEach { it.close() } }

    @Test
    fun `pool size tracks acquired and released connections`(): Unit = runBlocking {
        val db = pool(maxConnections = 2)
        val a = db.acquire().getOrThrow()
        val b = db.acquire().getOrThrow()
        assertAll {
            assertThat(db.poolSize(), "size while acquired").isEqualTo(2)
            assertThat(db.poolIdleSize(), "idle while acquired").isEqualTo(0)
        }
        a.close().getOrThrow()
        b.close().getOrThrow()
        // The native driver (sqlx) returns a released connection to the pool in the background.
        withTimeout(2.seconds) { while (db.poolIdleSize() < 2) delay(10.milliseconds) }
        assertAll {
            assertThat(db.poolSize(), "size after release").isEqualTo(2)
            assertThat(db.poolIdleSize(), "idle after release").isEqualTo(2)
        }
    }

    @Test
    fun `acquire beyond maxConnections fails with PoolTimedOut`(): Unit = runBlocking {
        val db = pool(maxConnections = 1, acquireTimeout = 500.milliseconds)
        val held = db.acquire().getOrThrow()
        try {
            val res = db.acquire()
            assertThat(res).isFailure()
            assertThat((res.exceptionOrNull() as SQLError).code).isEqualTo(SQLError.Code.PoolTimedOut)
        } finally {
            held.close().getOrThrow()
        }
    }

    @Test
    fun `a waiting acquire gets the released connection`(): Unit = runBlocking {
        val db = pool(maxConnections = 1)
        val held = db.acquire().getOrThrow()
        val waiter = async { db.acquire() }
        delay(200.milliseconds)
        held.close().getOrThrow()
        val cn = waiter.await().getOrThrow()
        try {
            assertThat(cn.fetchAll("select 1;").getOrThrow().first().get(0).asInt()).isEqualTo(1)
        } finally {
            cn.close().getOrThrow()
        }
    }

    @Test
    fun `concurrent queries share a small pool`(): Unit = runBlocking {
        val db = pool(maxConnections = 2)
        val results =
            (1..50).map { i -> async { db.fetchAll("select $i;").getOrThrow().first().get(0).asInt() } }.awaitAll()
        assertAll {
            assertThat(results).isEqualTo((1..50).toList())
            assertThat(db.poolSize()).isLessThanOrEqualTo(2)
        }
    }

    @Test
    fun `queries after close fail with PoolClosed`(): Unit = runBlocking {
        val db = pool(maxConnections = 2)
        db.fetchAll("select 1;").getOrThrow()
        db.close().getOrThrow()
        val res = db.fetchAll("select 1;")
        assertThat(res).isFailure()
        assertThat((res.exceptionOrNull() as SQLError).code).isEqualTo(SQLError.Code.PoolClosed)
    }

    @Test
    fun `close should be idempotent`(): Unit = runBlocking {
        val db = pool(maxConnections = 2)
        db.fetchAll("select 1;").getOrThrow()
        assertThat(db.close()).isSuccess()
        assertThat(db.close()).isSuccess()
    }

    // A query cancelled midway (here by a timeout) must still give its connection back to the pool, both when the
    // pool runs it and when the caller closes an acquired connection in `finally`.
    @Test
    fun `a cancelled query gives its connection back to the pool`(): Unit = runBlocking {
        val db = pool(maxConnections = 1, acquireTimeout = 3.seconds)
        val slow = "select pg_sleep(1)"
        repeat(3) {
            withTimeoutOrNull(100.milliseconds) { db.execute(slow) }
        }
        repeat(3) {
            withTimeoutOrNull(100.milliseconds) {
                val cn = db.acquire().getOrThrow()
                try {
                    cn.execute(slow)
                } finally {
                    cn.close()
                }
            }
        }
        // The pool's only connection is still there to serve a query.
        assertThat(db.fetchAll("select 1").getOrThrow().size).isEqualTo(1)
    }

    // A connection closed while a transaction begun on it is still open must not go back to the pool inside it:
    // the next borrower's writes would run in that transaction and never be committed.
    @Test
    fun `closing a connection rolls back its unfinished transaction`(): Unit = runBlocking {
        val db = pool(maxConnections = 1)
        val other = pool(maxConnections = 1)
        val table = "t_unfinished_${Random.nextInt(1_000_000)}"
        db.execute("create table $table(id int primary key)").getOrThrow()
        try {
            val cn = db.acquire().getOrThrow()
            val tx = cn.begin().getOrThrow()
            tx.execute("insert into $table(id) values (1)").getOrThrow()
            cn.close().getOrThrow() // neither committed nor rolled back

            // The transaction can no longer be used through the connection that went back to the pool.
            assertThat(tx.execute("insert into $table(id) values (3)")).isFailure()
            // The pool's only connection is out of that transaction: its insert is gone, and a new write is
            // committed (another pool sees it).
            db.execute("insert into $table(id) values (2)").getOrThrow()
            assertThat(other.fetchAll("select id from $table").getOrThrow().map { it.get(0).asInt() })
                .isEqualTo(listOf(2))
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    // A connection closed with a changed isolation level and a transaction still open must go back to the pool with
    // the default level: resetting it inside the open transaction was undone by the rollback on release.
    @Test
    fun `closing a connection mid-transaction resets its isolation level`(): Unit = runBlocking {
        val db = pool(maxConnections = 1)
        val cn = db.acquire().getOrThrow()
        cn.setTransactionIsolationLevel(Transaction.IsolationLevel.Serializable).getOrThrow()
        cn.begin().getOrThrow()
        cn.close().getOrThrow() // the transaction is neither committed nor rolled back

        val level = db.fetchAll("show transaction_isolation").getOrThrow().first().get(0).asString()
        assertThat(level).isEqualTo("read committed")
    }

    // Queries cancelled at any point, including while the pool hands them a connection, must not keep it.
    @Test
    fun `queries cancelled at random points give their connections back`(): Unit = runBlocking {
        val db = pool(maxConnections = 2, acquireTimeout = 3.seconds)
        repeat(500) {
            coroutineScope {
                repeat(4) {
                    launch {
                        withTimeoutOrNull(Random.nextInt(0, 4).milliseconds) { db.fetchAll("select 1").getOrThrow() }
                    }
                }
            }
        }
        // Both connections are back: two queries can still hold one each.
        val cn1 = db.acquire().getOrThrow()
        val cn2 = db.acquire().getOrThrow()
        cn1.close().getOrThrow()
        cn2.close().getOrThrow()
    }

    // Closing a connection from a cancelled coroutine, while a query still holds it, must still give it back.
    @Test
    fun `a connection closed from a cancelled coroutine goes back to the pool`(): Unit = runBlocking {
        val db = pool(maxConnections = 1, acquireTimeout = 3.seconds)
        val cn = db.acquire().getOrThrow()
        val busy = launch { cn.fetchAll("select pg_sleep(0.5)").getOrThrow() }
        delay(100.milliseconds)
        launch {
            coroutineContext.cancel()
            cn.close()
        }.join()
        busy.join()
        // The pool's only connection is back.
        assertThat(db.fetchAll("select 1").getOrThrow().size).isEqualTo(1)
    }

    // Closing the pool does not wait for a connection that is still checked out (it is closed when given back).
    @Test
    fun `closing the pool does not wait for a checked-out connection`(): Unit = runBlocking {
        val db = pool(maxConnections = 2)
        db.fetchAll("select 1").getOrThrow() // an idle connection as well
        val cn = db.acquire().getOrThrow()
        withTimeout(5.seconds) { db.close().getOrThrow() }
        assertThat(db.fetchAll("select 1")).isFailure()
        cn.close()
    }
}

