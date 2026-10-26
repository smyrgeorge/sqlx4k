@file:Suppress("SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Pool tests of the waits for a permit the JVM and Android driver takes before waiting for a connection of the native
 * pool (the native targets wait in the native pool alone).
 */
class JvmSQLiteCipherPoolTests {

    private val opened = mutableListOf<ISQLiteCipher>()

    @AfterTest
    fun tearDown(): Unit = runBlocking { opened.forEach { it.close() } }

    // Callers that time out waiting for a connection, some just as one is given back to them, must not keep any of the
    // pool's connections: every one of them can still be acquired afterwards.
    @Test
    fun `acquire timeouts racing with releases keep the pool's capacity`(): Unit = runBlocking {
        val options = ConnectionPool.Options.builder()
            .minConnections(2)
            .maxConnections(2)
            .acquireTimeout(2.milliseconds)
            .build()
        // Opening the pool opens (and keys) a connection within the acquire timeout, which it exceeds now and then.
        val db = withTimeout(30.seconds) {
            var db: ISQLiteCipher? = null
            while (db == null) db = runCatching { testSQLiteCipher("JvmSQLiteCipherPoolTests", options) }.getOrNull()
            db
        }.also { opened += it }
        // Both connections are opened (and keyed) up front: opening one takes longer than the acquire timeout.
        withTimeout(10.seconds) { while (db.poolIdleSize() < 2) delay(10.milliseconds) }
        withContext(Dispatchers.Default) {
            repeat(20) {
                (1..100).map { async { db.fetchAll("select 1;") } }.awaitAll()
            }
        }
        withTimeout(10.seconds) { while (db.poolIdleSize() < 2) delay(10.milliseconds) }
        val a = db.acquire().getOrThrow()
        try {
            db.acquire().getOrThrow().close().getOrThrow()
        } finally {
            a.close().getOrThrow()
        }
    }
}
