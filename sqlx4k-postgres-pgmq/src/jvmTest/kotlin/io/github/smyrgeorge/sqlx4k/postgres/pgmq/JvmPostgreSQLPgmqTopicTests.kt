package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.PgmqDbAdapterImpl
import io.github.smyrgeorge.sqlx4k.postgres.postgreSQL
import kotlin.test.Test

class JvmPostgreSQLPgmqTopicTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = postgreSQL(
        url = "postgresql://localhost:15432/test",
        username = "postgres",
        password = "postgres",
        options = options
    )

    private val client = PgmqClient(
        pg = PgmqDbAdapterImpl(db),
        options = PgmqClient.Options(
            autoInstall = true,
            verifyInstallation = true
        )
    )

    private val runner = CommonPostgreSQLPgmqTopicTests(client)

    @Test
    fun `sendTopic routes a message to a queue whose pattern matches`() {
        runner.`sendTopic routes a message to a queue whose pattern matches`()
    }

    @Test
    fun `sendTopic preserves headers on the routed message`() {
        runner.`sendTopic preserves headers on the routed message`()
    }

    @Test
    fun `sendTopic fans out to every matching queue`() {
        runner.`sendTopic fans out to every matching queue`()
    }

    @Test
    fun `star matches a single segment only`() {
        runner.`star matches a single segment only`()
    }

    @Test
    fun `hash matches multiple segments`() {
        runner.`hash matches multiple segments`()
    }

    @Test
    fun `sendTopic returns zero when no pattern matches`() {
        runner.`sendTopic returns zero when no pattern matches`()
    }

    @Test
    fun `bindTopic with multiple patterns binds them all`() {
        runner.`bindTopic with multiple patterns binds them all`()
    }

    @Test
    fun `unbindTopic stops routing and reports removal`() {
        runner.`unbindTopic stops routing and reports removal`()
    }

    @Test
    fun `sendTopic honours a delivery delay`() {
        runner.`sendTopic honours a delivery delay`()
    }
}
