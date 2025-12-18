package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.PgMqDbAdapterImpl
import io.github.smyrgeorge.sqlx4k.postgres.postgreSQL
import kotlin.test.Test

class JvmPostgreSQLPgMqConsumerTests {

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

    private val runner = CommonPostgreSQLPgMqConsumerTests(client)

    @Test
    fun `consumer should process messages automatically`() {
        runner.`consumer should process messages automatically`()
    }

    @Test
    fun `consumer should retry failed messages with backoff`() {
        runner.`consumer should retry failed messages with backoff`()
    }

    @Test
    fun `consumer should stop gracefully`() {
        runner.`consumer should stop gracefully`()
    }

    @Test
    fun `consumer should handle multiple messages with prefetch limit`() {
        runner.`consumer should handle multiple messages with prefetch limit`()
    }

    @Test
    fun `consumer should handle error callbacks properly`() {
        runner.`consumer should handle error callbacks properly`()
    }

    @Test
    fun `consumer should process messages in order when prefetch is 1`() {
        runner.`consumer should process messages in order when prefetch is 1`()
    }

    @Test
    fun `consumer should handle messages with headers`() {
        runner.`consumer should handle messages with headers`()
    }

    @Test
    fun `consumer should respect visibility timeout`() {
        runner.`consumer should respect visibility timeout`()
    }

    @Test
    fun `consumer should work with batch message sending`() {
        runner.`consumer should work with batch message sending`()
    }

    @Test
    fun `consumer metrics should reflect queue state`() {
        runner.`consumer metrics should reflect queue state`()
    }

    @Test
    fun `consumer should handle empty queue gracefully`() {
        runner.`consumer should handle empty queue gracefully`()
    }
}
