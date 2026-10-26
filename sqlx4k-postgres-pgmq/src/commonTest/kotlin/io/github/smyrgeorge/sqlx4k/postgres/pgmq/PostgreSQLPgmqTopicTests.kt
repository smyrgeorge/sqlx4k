@file:Suppress("SqlNoDataSourceInspection", "SqlResolve", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PostgreSQLPgmqTopicTests {

    private val db = testPostgreSQL()
    private val client = testPgmqClient(db)

    /**
     * The first segment of every pattern and routing key of the test: the JVM and native suites run at the same time
     * against the same database, and would otherwise route messages to each other's queues (and remove each other's
     * bindings).
     */
    private val root = "t${Random.nextInt(1_000_000)}"

    private fun topic(key: String): String = "$root.$key"

    // Topic routing (`bind_topic` / `unbind_topic` / `send_topic`) is part of the pgmq extension since 1.11.0. Its
    // bindings table is extension-owned, and dropping a queue leaves its bindings: the test's own are removed.
    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            client.pg.execute("DELETE FROM pgmq.topic_bindings WHERE pattern LIKE '$root.%'").getOrThrow()
        } finally {
            db.close().getOrThrow()
        }
    }

    private fun newQueue(): String = "test_topic_q_${Random.nextInt(1_000_000)}"

    @Test
    fun `sendTopic routes a message to a queue whose pattern matches`(): Unit = runBlocking {
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            client.bindTopic(topic("logs.*"), q).getOrThrow()

            val count = client.sendTopic(topic("logs.error"), """{"msg": "boom"}""").getOrThrow()
            assertThat(count).isEqualTo(1L)

            val messages = client.read(q, quantity = 10).getOrThrow()
            assertThat(messages).hasSize(1)
            assertThat(messages[0].message).isEqualTo("""{"msg": "boom"}""")
        } finally {
            client.drop(PgmqClient.Queue(q))
        }
    }

    @Test
    fun `sendTopic preserves headers on the routed message`(): Unit = runBlocking {
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            client.bindTopic(topic("orders.#"), q).getOrThrow()

            val headers = mapOf("trace" to "abc", "src" to "test")
            client.sendTopic(topic("orders.eu.created"), """{"id": 1}""", headers).getOrThrow()

            val messages = client.read(q, quantity = 10).getOrThrow()
            assertThat(messages).hasSize(1)
            assertThat(messages[0].headers).isEqualTo(headers)
        } finally {
            client.drop(PgmqClient.Queue(q))
        }
    }

    @Test
    fun `sendTopic fans out to every matching queue`(): Unit = runBlocking {
        val q1 = newQueue()
        val q2 = newQueue()
        client.create(PgmqClient.Queue(q1)).getOrThrow()
        client.create(PgmqClient.Queue(q2)).getOrThrow()
        try {
            // '#' matches zero-or-more segments, '*' matches exactly one — both match "app.user.created".
            client.bindTopic(topic("app.#"), q1).getOrThrow()
            client.bindTopic(topic("app.user.*"), q2).getOrThrow()

            val count = client.sendTopic(topic("app.user.created"), """{"user": 7}""").getOrThrow()
            assertThat(count).isEqualTo(2L)

            assertThat(client.read(q1, quantity = 10).getOrThrow()).hasSize(1)
            assertThat(client.read(q2, quantity = 10).getOrThrow()).hasSize(1)
        } finally {
            client.drop(PgmqClient.Queue(q1))
            client.drop(PgmqClient.Queue(q2))
        }
    }

    @Test
    fun `star matches a single segment only`(): Unit = runBlocking {
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            client.bindTopic(topic("logs.*"), q).getOrThrow()

            // One segment after "logs." matches; two segments do not.
            assertThat(client.sendTopic(topic("logs.error"), """{"m": 1}""").getOrThrow()).isEqualTo(1L)
            assertThat(client.sendTopic(topic("logs.error.fatal"), """{"m": 2}""").getOrThrow()).isEqualTo(0L)

            assertThat(client.read(q, quantity = 10).getOrThrow()).hasSize(1)
        } finally {
            client.drop(PgmqClient.Queue(q))
        }
    }

    @Test
    fun `hash matches multiple segments`(): Unit = runBlocking {
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            client.bindTopic(topic("logs.#"), q).getOrThrow()

            assertThat(client.sendTopic(topic("logs.error"), """{"m": 1}""").getOrThrow()).isEqualTo(1L)
            assertThat(client.sendTopic(topic("logs.error.fatal"), """{"m": 2}""").getOrThrow()).isEqualTo(1L)

            assertThat(client.read(q, quantity = 10).getOrThrow()).hasSize(2)
        } finally {
            client.drop(PgmqClient.Queue(q))
        }
    }

    @Test
    fun `sendTopic returns zero when no pattern matches`(): Unit = runBlocking {
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            client.bindTopic(topic("logs.*"), q).getOrThrow()

            val count = client.sendTopic(topic("metrics.cpu"), """{"m": 1}""").getOrThrow()
            assertThat(count).isEqualTo(0L)
            assertThat(client.read(q, quantity = 10).getOrThrow()).isEmpty()
        } finally {
            client.drop(PgmqClient.Queue(q))
        }
    }

    @Test
    fun `bindTopic with multiple patterns binds them all`(): Unit = runBlocking {
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            client.bindTopic(listOf(topic("a.*"), topic("b.*")), q).getOrThrow()

            assertThat(client.sendTopic(topic("a.one"), """{"m": 1}""").getOrThrow()).isEqualTo(1L)
            assertThat(client.sendTopic(topic("b.two"), """{"m": 2}""").getOrThrow()).isEqualTo(1L)
            assertThat(client.sendTopic(topic("c.three"), """{"m": 3}""").getOrThrow()).isEqualTo(0L)

            assertThat(client.read(q, quantity = 10).getOrThrow()).hasSize(2)
        } finally {
            client.drop(PgmqClient.Queue(q))
        }
    }

    @Test
    fun `unbindTopic stops routing and reports removal`(): Unit = runBlocking {
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            client.bindTopic(topic("logs.*"), q).getOrThrow()
            assertThat(client.sendTopic(topic("logs.error"), """{"m": 1}""").getOrThrow()).isEqualTo(1L)

            // Removing an existing binding returns true; a second removal returns false (idempotent).
            assertThat(client.unbindTopic(topic("logs.*"), q).getOrThrow()).isTrue()
            assertThat(client.unbindTopic(topic("logs.*"), q).getOrThrow()).isFalse()

            // After unbinding nothing is routed anymore.
            assertThat(client.sendTopic(topic("logs.error"), """{"m": 2}""").getOrThrow()).isEqualTo(0L)
        } finally {
            client.drop(PgmqClient.Queue(q))
        }
    }

    @Test
    fun `sendTopic honours a delivery delay`(): Unit = runBlocking {
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            client.bindTopic(topic("delayed.*"), q).getOrThrow()

            // A non-zero delay exercises the `delay integer` argument specifically.
            val count = client.sendTopic(topic("delayed.job"), """{"m": 1}""", delay = 2.seconds).getOrThrow()
            assertThat(count).isEqualTo(1L)

            // Delayed message is not visible immediately...
            assertThat(client.read(q, quantity = 10).getOrThrow()).isEmpty()
            // ...but becomes visible after the delay elapses.
            delay(2500.milliseconds)
            assertThat(client.read(q, quantity = 10).getOrThrow()).hasSize(1)
        } finally {
            client.drop(PgmqClient.Queue(q))
        }
    }

    // The bindings of a list are created all or none, and a failure is returned, not thrown.
    @Test
    fun `bindTopic with a list is atomic`(): Unit = runBlocking {
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            val res = client.bindTopic(listOf(topic("valid.*"), topic("invalid..pattern")), q)
            assertThat(res.isFailure).isTrue()

            val bound = client.pg.fetchAll(
                "SELECT count(*) FROM pgmq.topic_bindings WHERE queue_name = '$q'"
            ).getOrThrow().first().get(0).asLong()
            assertThat(bound).isEqualTo(0L)
        } finally {
            client.drop(PgmqClient.Queue(q))
        }
    }
}
