package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativeSQLiteCipherConnectionTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = SQLiteCipher(
        url = "sqlite://test.db".also { resetDatabaseFile(it) },
        password = "test-passphrase",
        options = options
    )

    private val runner = CommonSQLiteCipherConnectionTests(db)

    @Test
    fun `acquire-release should allow operations then forbid after release`() {
        runner.`acquire-release should allow operations then forbid after release`()
    }

    @Test
    fun `close should be idempotent`() {
        runner.`close should be idempotent`()
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
