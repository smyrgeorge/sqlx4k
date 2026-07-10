@file:Suppress("SqlNoDataSourceInspection", "SqlResolve")

package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.migrate.utils.readSqlFilesFromDisk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("SqlNoDataSourceInspection")
class CommonPostgreSQLPgmqTopicTests(
    private val client: PgmqClient
) {

    private fun newQueue(): String = "test_topic_q_${Random.nextInt(1_000_000)}"

    /**
     * Ensures the topic routing functions (`bind_topic` / `unbind_topic` / `send_topic`) are
     * available and starts each test from a clean set of bindings.
     *
     * Some pgmq builds already bundle the topic feature as part of the extension (in which case the
     * `pgmq.topic_bindings` table is extension-owned and cannot be dropped); others do not. So we
     * install `topics.sql` only when the functions are missing, and always reset the bindings with a
     * `DELETE` (which works regardless of extension ownership, unlike the `DROP TABLE` in the SQL
     * file). The test working directory is the module directory, so the file is under `src/sql`.
     */
    private suspend fun ensureTopics() {
        val hasSendTopic = client.pg.fetchAll(
            "SELECT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON p.pronamespace = n.oid " +
                    "WHERE n.nspname = 'pgmq' AND p.proname = 'send_topic')"
        ).getOrThrow().first().get(0).asBoolean()

        if (!hasSendTopic) {
            val topics = readSqlFilesFromDisk("src/sql").firstOrNull { it.first == "topics.sql" }
                ?: error("Could not locate topics.sql under src/sql (cwd must be the module directory).")
            client.installExtension(topics)
        }

        client.pg.execute("DELETE FROM pgmq.topic_bindings").getOrThrow()
    }

    fun `sendTopic routes a message to a queue whose pattern matches`() = runBlocking {
        ensureTopics()
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            client.bindTopic("logs.*", q).getOrThrow()

            val count = client.sendTopic("logs.error", """{"msg": "boom"}""").getOrThrow()
            assertThat(count).isEqualTo(1L)

            val messages = client.read(q, quantity = 10).getOrThrow()
            assertThat(messages).hasSize(1)
            assertThat(messages[0].message).isEqualTo("""{"msg": "boom"}""")
        } finally {
            client.drop(PgmqClient.Queue(q))
        }
    }

    fun `sendTopic preserves headers on the routed message`() = runBlocking {
        ensureTopics()
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            client.bindTopic("orders.#", q).getOrThrow()

            val headers = mapOf("trace" to "abc", "src" to "test")
            client.sendTopic("orders.eu.created", """{"id": 1}""", headers).getOrThrow()

            val messages = client.read(q, quantity = 10).getOrThrow()
            assertThat(messages).hasSize(1)
            assertThat(messages[0].headers).isEqualTo(headers)
        } finally {
            client.drop(PgmqClient.Queue(q))
        }
    }

    fun `sendTopic fans out to every matching queue`() = runBlocking {
        ensureTopics()
        val q1 = newQueue()
        val q2 = newQueue()
        client.create(PgmqClient.Queue(q1)).getOrThrow()
        client.create(PgmqClient.Queue(q2)).getOrThrow()
        try {
            // '#' matches zero-or-more segments, '*' matches exactly one — both match "app.user.created".
            client.bindTopic("app.#", q1).getOrThrow()
            client.bindTopic("app.user.*", q2).getOrThrow()

            val count = client.sendTopic("app.user.created", """{"user": 7}""").getOrThrow()
            assertThat(count).isEqualTo(2L)

            assertThat(client.read(q1, quantity = 10).getOrThrow()).hasSize(1)
            assertThat(client.read(q2, quantity = 10).getOrThrow()).hasSize(1)
        } finally {
            client.drop(PgmqClient.Queue(q1))
            client.drop(PgmqClient.Queue(q2))
        }
    }

    fun `star matches a single segment only`() = runBlocking {
        ensureTopics()
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            client.bindTopic("logs.*", q).getOrThrow()

            // One segment after "logs." matches; two segments do not.
            assertThat(client.sendTopic("logs.error", """{"m": 1}""").getOrThrow()).isEqualTo(1L)
            assertThat(client.sendTopic("logs.error.fatal", """{"m": 2}""").getOrThrow()).isEqualTo(0L)

            assertThat(client.read(q, quantity = 10).getOrThrow()).hasSize(1)
        } finally {
            client.drop(PgmqClient.Queue(q))
        }
    }

    fun `hash matches multiple segments`() = runBlocking {
        ensureTopics()
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            client.bindTopic("logs.#", q).getOrThrow()

            assertThat(client.sendTopic("logs.error", """{"m": 1}""").getOrThrow()).isEqualTo(1L)
            assertThat(client.sendTopic("logs.error.fatal", """{"m": 2}""").getOrThrow()).isEqualTo(1L)

            assertThat(client.read(q, quantity = 10).getOrThrow()).hasSize(2)
        } finally {
            client.drop(PgmqClient.Queue(q))
        }
    }

    fun `sendTopic returns zero when no pattern matches`() = runBlocking {
        ensureTopics()
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            client.bindTopic("logs.*", q).getOrThrow()

            val count = client.sendTopic("metrics.cpu", """{"m": 1}""").getOrThrow()
            assertThat(count).isEqualTo(0L)
            assertThat(client.read(q, quantity = 10).getOrThrow()).isEmpty()
        } finally {
            client.drop(PgmqClient.Queue(q))
        }
    }

    fun `bindTopic with multiple patterns binds them all`() = runBlocking {
        ensureTopics()
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            client.bindTopic(listOf("a.*", "b.*"), q).getOrThrow()

            assertThat(client.sendTopic("a.one", """{"m": 1}""").getOrThrow()).isEqualTo(1L)
            assertThat(client.sendTopic("b.two", """{"m": 2}""").getOrThrow()).isEqualTo(1L)
            assertThat(client.sendTopic("c.three", """{"m": 3}""").getOrThrow()).isEqualTo(0L)

            assertThat(client.read(q, quantity = 10).getOrThrow()).hasSize(2)
        } finally {
            client.drop(PgmqClient.Queue(q))
        }
    }

    fun `unbindTopic stops routing and reports removal`() = runBlocking {
        ensureTopics()
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            client.bindTopic("logs.*", q).getOrThrow()
            assertThat(client.sendTopic("logs.error", """{"m": 1}""").getOrThrow()).isEqualTo(1L)

            // Removing an existing binding returns true; a second removal returns false (idempotent).
            assertThat(client.unbindTopic("logs.*", q).getOrThrow()).isTrue()
            assertThat(client.unbindTopic("logs.*", q).getOrThrow()).isFalse()

            // After unbinding nothing is routed anymore.
            assertThat(client.sendTopic("logs.error", """{"m": 2}""").getOrThrow()).isEqualTo(0L)
        } finally {
            client.drop(PgmqClient.Queue(q))
        }
    }

    fun `sendTopic honours a delivery delay`() = runBlocking {
        ensureTopics()
        val q = newQueue()
        client.create(PgmqClient.Queue(q)).getOrThrow()
        try {
            client.bindTopic("delayed.*", q).getOrThrow()

            // A non-zero delay exercises the `delay integer` argument specifically.
            val count = client.sendTopic("delayed.job", """{"m": 1}""", delay = 2.seconds).getOrThrow()
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
}
