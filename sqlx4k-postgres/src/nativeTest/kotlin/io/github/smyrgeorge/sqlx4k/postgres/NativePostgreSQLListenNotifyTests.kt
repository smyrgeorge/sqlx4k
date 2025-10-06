package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativePostgreSQLListenNotifyTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = postgreSQL(
        url = "postgresql://localhost:15432/test",
        username = "postgres",
        password = "postgres",
        options = options
    )

    private val runner = CommonPostgreSQLListenNotifyTests(db)

    @Test
    fun `listen on single channel should receive notifications`() {
        runner.`listen on single channel should receive notifications`()
    }

    @Test
    fun `listen on multiple channels should route notifications`() {
        runner.`listen on multiple channels should route notifications`()
    }

    @Test
    fun `validateChannelName should fail for invalid names`() {
        runner.`validateChannelName should fail for invalid names`()
    }

    @Test
    fun `notify without listener should not fail`() {
        runner.`notify without listener should not fail`()
    }

    @Test
    fun `multiple notifications should be delivered`() {
        runner.`multiple notifications should be delivered`()
    }
}
