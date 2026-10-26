@file:Suppress("SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isSuccess
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SQLiteCipherPoolTests : SQLiteCipherTestBase() {

    private val opened = mutableListOf<ISQLiteCipher>()

    private fun pool(maxConnections: Int, acquireTimeout: Duration = 5.seconds): ISQLiteCipher {
        val options = ConnectionPool.Options.builder()
            .maxConnections(maxConnections)
            .acquireTimeout(acquireTimeout)
            .build()
        return testSQLiteCipher("SQLiteCipherPoolTests", options = options).also { opened += it }
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
        val slow =
            "with recursive c(x) as (select 1 union all select x + 1 from c where x < 5000000) select count(*) from c"
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
        val options = ConnectionPool.Options.builder().maxConnections(1).acquireTimeout(3.seconds).build()
        val db = pool(maxConnections = 1, acquireTimeout = 3.seconds)
        db.execute("create table t_unfinished(id integer primary key);").getOrThrow()

        val cn = db.acquire().getOrThrow()
        val tx = cn.begin().getOrThrow()
        tx.execute("insert into t_unfinished(id) values (1);").getOrThrow()
        cn.close().getOrThrow() // neither committed nor rolled back

        // The pool's only connection is no longer in that transaction: its insert is gone, and the transaction
        // can no longer be used through the connection that went back to the pool.
        assertThat(db.fetchAll("select count(*) from t_unfinished;").getOrThrow().first().get(0).asInt()).isEqualTo(0)
        assertThat(tx.execute("insert into t_unfinished(id) values (3);")).isFailure()

        // A new write is committed for real: it survives closing the pool and reopening the database.
        db.execute("insert into t_unfinished(id) values (2);").getOrThrow()
        db.close().getOrThrow()
        val reopened = openSQLiteCipher(testDatabaseUrl("SQLiteCipherPoolTests"), options).also { opened += it }
        assertThat(reopened.fetchAll("select id from t_unfinished;").getOrThrow().map { it.get(0).asInt() })
            .isEqualTo(listOf(2))
    }

    // More transactions than the IO dispatcher has threads, on a small pool: waiting for a connection must not take up
    // the threads the connections' holders need to run their statements and commit.
    @Test
    fun `many more concurrent transactions than connections complete`(): Unit = runBlocking {
        val db = pool(maxConnections = 2, acquireTimeout = 60.seconds)
        db.execute("create table if not exists t_many(id integer primary key);").getOrThrow()
        withTimeout(30.seconds) {
            withContext(Dispatchers.Default) {
                (1..200).map {
                    async { db.transaction { execute("insert into t_many default values;").getOrThrow() } }
                }.awaitAll()
            }
        }
        assertThat(db.fetchAll("select count(*) from t_many;").getOrThrow().first().get(0).asInt()).isEqualTo(200)
        db.execute("drop table t_many;").getOrThrow()
    }
}

