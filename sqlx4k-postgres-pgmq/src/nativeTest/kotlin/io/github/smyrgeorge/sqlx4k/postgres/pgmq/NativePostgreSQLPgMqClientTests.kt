package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.PgMqDbAdapterImpl
import io.github.smyrgeorge.sqlx4k.postgres.postgreSQL
import kotlin.test.Test

class NativePostgreSQLPgMqClientTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = postgreSQL(
        url = "postgresql://localhost:15432/test",
        username = "postgres",
        password = "postgres",
        options = options
    )

    private val client = PgMqClient(
        pg = PgMqDbAdapterImpl(db),
        options = PgMqClient.Options(
            autoInstall = true,
            verifyInstallation = true
        )
    )

    private val runner = CommonPostgreSQLPgMqClientTests(client)

    @Test
    fun `create queue should succeed`() {
        runner.`create queue should succeed`()
    }

    @Test
    fun `create unlogged queue should succeed`() {
        runner.`create unlogged queue should succeed`()
    }

    @Test
    fun `send and read single message should work`() {
        runner.`send and read single message should work`()
    }

    @Test
    fun `send message with headers should preserve headers`() {
        runner.`send message with headers should preserve headers`()
    }

    @Test
    fun `send batch messages should enqueue all`() {
        runner.`send batch messages should enqueue all`()
    }

    @Test
    fun `pop message should remove from queue`() {
        runner.`pop message should remove from queue`()
    }

    @Test
    fun `pop multiple messages should work`() {
        runner.`pop multiple messages should work`()
    }

    @Test
    fun `archive message should move to archive`() {
        runner.`archive message should move to archive`()
    }

    @Test
    fun `archive multiple messages should work`() {
        runner.`archive multiple messages should work`()
    }

    @Test
    fun `delete message should remove from queue`() {
        runner.`delete message should remove from queue`()
    }

    @Test
    fun `delete multiple messages should work`() {
        runner.`delete multiple messages should work`()
    }

    @Test
    fun `purge queue should remove all messages`() {
        runner.`purge queue should remove all messages`()
    }

    @Test
    fun `setVt should update visibility timeout`() {
        runner.`setVt should update visibility timeout`()
    }

    @Test
    fun `ack should delete message`() {
        runner.`ack should delete message`()
    }

    @Test
    fun `nack should reset visibility timeout`() {
        runner.`nack should reset visibility timeout`()
    }

    @Test
    fun `metrics should return queue statistics`() {
        runner.`metrics should return queue statistics`()
    }

    @Test
    fun `delayed message should not be visible immediately`() {
        runner.`delayed message should not be visible immediately`()
    }

    @Test
    fun `drop queue should succeed`() {
        runner.`drop queue should succeed`()
    }

    @Test
    fun `listQueues should return all queues`() {
        runner.`listQueues should return all queues`()
    }

    @Test
    fun `message readCt should increment on repeated reads`() {
        runner.`message readCt should increment on repeated reads`()
    }
}
