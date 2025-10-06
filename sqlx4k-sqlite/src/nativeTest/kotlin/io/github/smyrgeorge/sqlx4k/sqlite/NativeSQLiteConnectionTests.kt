package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativeSQLiteConnectionTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = SQLite(
        url = "sqlite://test.db",
        options = options
    )

    private val runner = CommonSQLiteConnectionTests(db)

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
