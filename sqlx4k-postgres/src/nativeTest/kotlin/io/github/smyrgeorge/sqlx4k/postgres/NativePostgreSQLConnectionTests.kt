package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativePostgreSQLConnectionTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = postgreSQL(
        url = "postgresql://localhost:15432/test",
        username = "postgres",
        password = "postgres",
        options = options
    )

    private val runner = CommonPostgreSQLConnectionTests(db)

    @Test
    fun `acquire-release should allow operations then forbid after release`() {
        runner.`acquire-release should allow operations then forbid after release`()
    }

    @Test
    fun `double release should fail with ConnectionIsReleased`() {
        runner.`double release should fail with ConnectionIsReleased`()
    }

    @Test
    fun `connection begin-commit and rollback should work`() {
        runner.`connection begin-commit and rollback should work`()
    }

    @Test
    fun `fetchAll and execute should work while acquired`() {
        runner.`fetchAll and execute should work while acquired`()
    }

    @Test
    fun `status should be Acquired then Released`() {
        runner.`status should be Acquired then Released`()
    }
}
